/*
 * Copyright 2002-2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.beans;

import java.beans.PropertyChangeEvent;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.lang.reflect.UndeclaredThrowableException;
import java.security.PrivilegedActionException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.core.CollectionFactory;
import org.springframework.core.ResolvableType;
import org.springframework.core.convert.ConversionException;
import org.springframework.core.convert.ConverterNotFoundException;
import org.springframework.core.convert.TypeDescriptor;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;

/**
 *  提供对嵌套属性的支持
 * A basic {@link ConfigurablePropertyAccessor} that provides the necessary
 * infrastructure for all typical use cases.
 *
 * <p>This accessor will convert collection and array values to the corresponding
 * target collections or arrays, if necessary. Custom property editors that deal
 * with collections or arrays can either be written via PropertyEditor's
 * {@code setValue}, or against a comma-delimited String via {@code setAsText},
 * as String arrays are converted in such a format if the array itself is not
 * assignable.
 *
 * @author Juergen Hoeller
 * @author Stephane Nicoll
 * @author Rod Johnson
 * @author Rob Harrop
 * @since 4.2
 * @see #registerCustomEditor
 * @see #setPropertyValues
 * @see #setPropertyValue
 * @see #getPropertyValue
 * @see #getPropertyType
 * @see BeanWrapper
 * @see PropertyEditorRegistrySupport
 */
public abstract class AbstractNestablePropertyAccessor extends AbstractPropertyAccessor {

	/**
	 * We'll create a lot of these objects, so we don't want a new logger every time.
	 */
	private static final Log logger = LogFactory.getLog(AbstractNestablePropertyAccessor.class);

	/**
	 * 允许集合增长的长度限制
	 */
	private int autoGrowCollectionLimit = Integer.MAX_VALUE;

	/**
	 * 当前包装的对象
	 */
	@Nullable
	Object wrappedObject;

	/**
	 * 当前 BeanWrapper 对象所属嵌套层次的属性名，最顶层的 BeanWrapper 此属性的值为空
	 */
	private String nestedPath = "";

	/**
	 * 最顶层 BeanWrapper 所包装的对象
	 */
	@Nullable
	Object rootObject;

	//嵌套属性访问器的缓存
	/** Map with cached nested Accessors: nested path -> Accessor instance. */
	@Nullable
	private Map<String, AbstractNestablePropertyAccessor> nestedPropertyAccessors;


	/**
	 * Create a new empty accessor. Wrapped instance needs to be set afterwards.
	 * Registers default editors.
	 * @see #setWrappedInstance
	 */
	protected AbstractNestablePropertyAccessor() {
		//默认是注册默认的属性编辑器的：defaultEditors  它几乎处理了所有的Java内置类型  包括基本类型、包装类型以及对应数组类型~~~
		this(true);
	}

	/**
	 * Create a new empty accessor. Wrapped instance needs to be set afterwards.
	 * @param registerDefaultEditors whether to register default editors
	 * (can be suppressed if the accessor won't need any type conversion)
	 * @see #setWrappedInstance
	 */
	protected AbstractNestablePropertyAccessor(boolean registerDefaultEditors) {
		//如果要注册默认的属性编辑器, 则注册
		if (registerDefaultEditors) {
			registerDefaultEditors();
		}
		//创建 TypeConverterDelegate
		this.typeConverterDelegate = new TypeConverterDelegate(this);
	}

	/**
	 * Create a new accessor for the given object.
	 * @param object object wrapped by this accessor
	 */
	protected AbstractNestablePropertyAccessor(Object object) {
		registerDefaultEditors();
		//设置包装的对象
		setWrappedInstance(object);
	}

	/**
	 * Create a new accessor, wrapping a new instance of the specified class.
	 * @param clazz class to instantiate and wrap
	 */
	protected AbstractNestablePropertyAccessor(Class<?> clazz) {
		registerDefaultEditors();
		//对类进行初始化, 并且设置包装的对象
		setWrappedInstance(BeanUtils.instantiateClass(clazz));
	}

	/**
	 * Create a new accessor for the given object,
	 * registering a nested path that the object is in.
	 * @param object object wrapped by this accessor
	 * @param nestedPath the nested path of the object
	 * @param rootObject the root object at the top of the path
	 */
	protected AbstractNestablePropertyAccessor(Object object, String nestedPath, Object rootObject) {
		registerDefaultEditors();
		setWrappedInstance(object, nestedPath, rootObject);
	}

	/**
	 * Create a new accessor for the given object,
	 * registering a nested path that the object is in.
	 * @param object object wrapped by this accessor
	 * @param nestedPath the nested path of the object
	 * @param parent the containing accessor (must not be {@code null})
	 */
	protected AbstractNestablePropertyAccessor(Object object, String nestedPath, AbstractNestablePropertyAccessor parent) {
		//设置包装对象
		setWrappedInstance(object, nestedPath, parent.getWrappedInstance());
		//复制父类的属性
		setExtractOldValueForEditor(parent.isExtractOldValueForEditor());
		setAutoGrowNestedPaths(parent.isAutoGrowNestedPaths());
		setAutoGrowCollectionLimit(parent.getAutoGrowCollectionLimit());
		setConversionService(parent.getConversionService());
	}


	/**
	 * Specify a limit for array and collection auto-growing.
	 * <p>Default is unlimited on a plain accessor.
	 */
	public void setAutoGrowCollectionLimit(int autoGrowCollectionLimit) {
		this.autoGrowCollectionLimit = autoGrowCollectionLimit;
	}

	/**
	 * Return the limit for array and collection auto-growing.
	 */
	public int getAutoGrowCollectionLimit() {
		return this.autoGrowCollectionLimit;
	}

	/**
	 * Switch the target object, replacing the cached introspection results only
	 * if the class of the new object is different to that of the replaced object.
	 * @param object the new target object
	 */
	public void setWrappedInstance(Object object) {
		setWrappedInstance(object, "", null);
	}

	/**
	 * 设计包装的对象
	 * Switch the target object, replacing the cached introspection results only
	 * if the class of the new object is different to that of the replaced object.
	 * @param object the new target object
	 * @param nestedPath the nested path of the object
	 * @param rootObject the root object at the top of the path
	 */
	public void setWrappedInstance(Object object, @Nullable String nestedPath, @Nullable Object rootObject) {
		//如果对象有 Optional , 则去掉
		this.wrappedObject = ObjectUtils.unwrapOptional(object);
		Assert.notNull(this.wrappedObject, "Target object must not be null");
		this.nestedPath = (nestedPath != null ? nestedPath : "");
		this.rootObject = (!this.nestedPath.isEmpty() ? rootObject : this.wrappedObject);
		this.nestedPropertyAccessors = null;
		//创建 TypeConverterDelegate
		this.typeConverterDelegate = new TypeConverterDelegate(this, this.wrappedObject);
	}

	public final Object getWrappedInstance() {
		Assert.state(this.wrappedObject != null, "No wrapped object");
		return this.wrappedObject;
	}

	public final Class<?> getWrappedClass() {
		return getWrappedInstance().getClass();
	}

	/**
	 * Return the nested path of the object wrapped by this accessor.
	 */
	public final String getNestedPath() {
		return this.nestedPath;
	}

	/**
	 * Return the root object at the top of the path of this accessor.
	 * @see #getNestedPath
	 */
	public final Object getRootInstance() {
		Assert.state(this.rootObject != null, "No root object");
		return this.rootObject;
	}

	/**
	 * Return the class of the root object at the top of the path of this accessor.
	 * @see #getNestedPath
	 */
	public final Class<?> getRootClass() {
		return getRootInstance().getClass();
	}

	@Override
	public void setPropertyValue(String propertyName, @Nullable Object value) throws BeansException {
		AbstractNestablePropertyAccessor nestedPa;
		try {
			//获取给定属性的属性访问器
			nestedPa = getPropertyAccessorForPropertyPath(propertyName);
		}
		catch (NotReadablePropertyException ex) {
			throw new NotWritablePropertyException(getRootClass(), this.nestedPath + propertyName,
					"Nested property in path '" + propertyName + "' does not exist", ex);
		}
		//获取最后一层属性的 PropertyTokenHolder
		PropertyTokenHolder tokens = getPropertyNameTokens(getFinalPath(nestedPa, propertyName));
		//设置属性值
		nestedPa.setPropertyValue(tokens, new PropertyValue(propertyName, value));
	}

	@Override
	public void setPropertyValue(PropertyValue pv) throws BeansException {
		//从缓存中获取 PropertyTokenHolder
		PropertyTokenHolder tokens = (PropertyTokenHolder) pv.resolvedTokens;
		//如果缓存没有
		if (tokens == null) {
			//获取属性名
			String propertyName = pv.getName();
			AbstractNestablePropertyAccessor nestedPa;
			try {
				//获取属性的访问器
				nestedPa = getPropertyAccessorForPropertyPath(propertyName);
			}
			catch (NotReadablePropertyException ex) {
				throw new NotWritablePropertyException(getRootClass(), this.nestedPath + propertyName,
						"Nested property in path '" + propertyName + "' does not exist", ex);
			}
			//获取最后一个属性的 PropertyTokenHolder
			tokens = getPropertyNameTokens(getFinalPath(nestedPa, propertyName));
			//如果属性访问器是当前的
			if (nestedPa == this) {
				pv.getOriginalPropertyValue().resolvedTokens = tokens;
			}
			nestedPa.setPropertyValue(tokens, pv);
		}
		else {
			//设置值
			setPropertyValue(tokens, pv);
		}
	}

	protected void setPropertyValue(PropertyTokenHolder tokens, PropertyValue pv) throws BeansException {
		//如果有 keys, 则表明是集合或Map 类型
		if (tokens.keys != null) {
			processKeyedProperty(tokens, pv);
		}
		else {
			//处理普通的本地属性
			processLocalProperty(tokens, pv);
		}
	}

	/**
	 * 给嵌套属性设置值, 如 attrs[key][0]
	 */
	@SuppressWarnings("unchecked")
	private void processKeyedProperty(PropertyTokenHolder tokens, PropertyValue pv) {
		//获取对应属性的上一层对象
		Object propValue = getPropertyHoldingValue(tokens);
		//获取当前属性的处理器
		PropertyHandler ph = getLocalPropertyHandler(tokens.actualName);
		//如果属性处理器为 null , 则报异常
		if (ph == null) {
			throw new InvalidPropertyException(
					getRootClass(), this.nestedPath + tokens.actualName, "No property handler found");
		}
		Assert.state(tokens.keys != null, "No token keys");
		//获取最后一个 key
		String lastKey = tokens.keys[tokens.keys.length - 1];

		//如果当前 propValue 是数组
		if (propValue.getClass().isArray()) {
			//获取数组元素类型
			Class<?> requiredType = propValue.getClass().getComponentType();
			//获取索引
			int arrayIndex = Integer.parseInt(lastKey);
			Object oldValue = null;
			try {
				//如果抽取旧值且数组索引小于数组的长度
				if (isExtractOldValueForEditor() && arrayIndex < Array.getLength(propValue)) {
					//获取原来的值
					oldValue = Array.get(propValue, arrayIndex);
				}
				//获取属性当前嵌套的类型, 并且转换值
				Object convertedValue = convertIfNecessary(tokens.canonicalName, oldValue, pv.getValue(),
						requiredType, ph.nested(tokens.keys.length));
				//获取数组长度
				int length = Array.getLength(propValue);
				//如果指定索引大于数组长度且不于集合长度限制
				if (arrayIndex >= length && arrayIndex < this.autoGrowCollectionLimit) {
					//获取元素类型
					Class<?> componentType = propValue.getClass().getComponentType();
					//创建给定元素的数组, 并指定合适的长度
					Object newArray = Array.newInstance(componentType, arrayIndex + 1);
					//复制原来数组的内容
					System.arraycopy(propValue, 0, newArray, 0, length);
					//将当前数组设置到给定属性中去
					//todo wolfleong 感觉这里是不是搞错了, 这个数组不是在属性顶层的, 这里应该传处理过的 canonicalName , canonicalName 减少一个 key
					setPropertyValue(tokens.actualName, newArray);
					//再根据属性名获取值
					propValue = getPropertyValue(tokens.actualName);
				}
				//设置转换值到指定索引用
				Array.set(propValue, arrayIndex, convertedValue);
			}
			catch (IndexOutOfBoundsException ex) {
				throw new InvalidPropertyException(getRootClass(), this.nestedPath + tokens.canonicalName,
						"Invalid array index in property path '" + tokens.canonicalName + "'", ex);
			}
		}
		//如果值是列表
		else if (propValue instanceof List) {
			//获取元素类型
			Class<?> requiredType = ph.getCollectionType(tokens.keys.length);
			//强转成列表
			List<Object> list = (List<Object>) propValue;
			//解析下标
			int index = Integer.parseInt(lastKey);
			Object oldValue = null;
			//如果要提取旧值且下标是小于列表长度
			if (isExtractOldValueForEditor() && index < list.size()) {
				//根据下标获取旧值
				oldValue = list.get(index);
			}
			//转换当前的值
			Object convertedValue = convertIfNecessary(tokens.canonicalName, oldValue, pv.getValue(),
					requiredType, ph.nested(tokens.keys.length));
			//获取列表长度
			int size = list.size();
			//如果指定索引大于列表最大长度且小于自动增长长度限制
			if (index >= size && index < this.autoGrowCollectionLimit) {
				//扩展列表长度, 值添加为 null , 直到 index 位置
				for (int i = size; i < index; i++) {
					try {
						list.add(null);
					}
					catch (NullPointerException ex) {
						throw new InvalidPropertyException(getRootClass(), this.nestedPath + tokens.canonicalName,
								"Cannot set element with index " + index + " in List of size " +
								size + ", accessed using property path '" + tokens.canonicalName +
								"': List does not support filling up gaps with null elements");
					}
				}
				//在指定位置添加给定的值
				list.add(convertedValue);
			}
			//如果指定索引是小于列表长度的, 可以直接指定位置替换值
			else {
				try {
					list.set(index, convertedValue);
				}
				catch (IndexOutOfBoundsException ex) {
					throw new InvalidPropertyException(getRootClass(), this.nestedPath + tokens.canonicalName,
							"Invalid list index in property path '" + tokens.canonicalName + "'", ex);
				}
			}
		}
        //如果是 Map
		else if (propValue instanceof Map) {
			//获取 map 的 key 的类型
			Class<?> mapKeyType = ph.getMapKeyType(tokens.keys.length);
			//获取 map 的 value 的类型
			Class<?> mapValueType = ph.getMapValueType(tokens.keys.length);
			//强转
			Map<Object, Object> map = (Map<Object, Object>) propValue;
			// IMPORTANT: Do not pass full property name in here - property editors
			// must not kick in for map keys but rather only for map values.
			//创建 key 的类型描述器
			TypeDescriptor typeDescriptor = TypeDescriptor.valueOf(mapKeyType);
			//将 Map 的 key 转换成给定的类型
			Object convertedMapKey = convertIfNecessary(null, null, lastKey, mapKeyType, typeDescriptor);
			Object oldValue = null;
			//如果提取旧值
			if (isExtractOldValueForEditor()) {
				//根据转换的 key 获取原来的值
				oldValue = map.get(convertedMapKey);
			}
			// Pass full property name and old value in here, since we want full
			// conversion ability for map values.
			//根据值的类型进行转换
			Object convertedMapValue = convertIfNecessary(tokens.canonicalName, oldValue, pv.getValue(),
					mapValueType, ph.nested(tokens.keys.length));
			//添加到 map 中
			map.put(convertedMapKey, convertedMapValue);
		}

		else {
			throw new InvalidPropertyException(getRootClass(), this.nestedPath + tokens.canonicalName,
					"Property referenced in indexed property path '" + tokens.canonicalName +
					"' is neither an array nor a List nor a Map; returned value was [" + propValue + "]");
		}
	}

	/**
	 * 获取属性值的上一层对象, 获取 attrs['key'][0] 中 attrs['key'] 的对象
	 */
	private Object getPropertyHoldingValue(PropertyTokenHolder tokens) {
		// Apply indexes and map keys: fetch value for all keys but the last one.
		Assert.state(tokens.keys != null, "No token keys");
		//创建新的 PropertyTokenHolder
		PropertyTokenHolder getterTokens = new PropertyTokenHolder(tokens.actualName);
		//设计 canonicalName
		getterTokens.canonicalName = tokens.canonicalName;
		//创建少一个 key 的数组
		getterTokens.keys = new String[tokens.keys.length - 1];
		//复制 key, 最终会得到 [key], 没有 0
		System.arraycopy(tokens.keys, 0, getterTokens.keys, 0, tokens.keys.length - 1);

		Object propValue;
		try {
			//根据新的 PropertyTokenHolder 获取属性的值
			propValue = getPropertyValue(getterTokens);
		}
		catch (NotReadablePropertyException ex) {
			throw new NotWritablePropertyException(getRootClass(), this.nestedPath + tokens.canonicalName,
					"Cannot access indexed value in property referenced " +
					"in indexed property path '" + tokens.canonicalName + "'", ex);
		}

		//如果属性的值为 null
		if (propValue == null) {
			//如果自动生成默认值
			// null map value case
			if (isAutoGrowNestedPaths()) {
				//从后面开始搜索, 获取最后一个 '['
				int lastKeyIndex = tokens.canonicalName.lastIndexOf('[');
				//截取当前对象的 canonicalName, 也就是 attrs[key]
				getterTokens.canonicalName = tokens.canonicalName.substring(0, lastKeyIndex);
				//设置默认值
				propValue = setDefaultValue(getterTokens);
			}
			else {
				throw new NullValueInNestedPathException(getRootClass(), this.nestedPath + tokens.canonicalName,
						"Cannot access indexed value in property referenced " +
						"in indexed property path '" + tokens.canonicalName + "': returned null");
			}
		}
		//返回
		return propValue;
	}

	/**
	 * 给没有嵌套的属性设置值, 也就是不带[]的
	 */
	private void processLocalProperty(PropertyTokenHolder tokens, PropertyValue pv) {
		//根据属性名, 获取属性处理器
		PropertyHandler ph = getLocalPropertyHandler(tokens.actualName);
		//如果属性处理器是 null, 或者属性不可写
		if (ph == null || !ph.isWritable()) {
			//如果属性是可选的, 则不处理
			if (pv.isOptional()) {
				if (logger.isDebugEnabled()) {
					logger.debug("Ignoring optional value for property '" + tokens.actualName +
							"' - property not found on bean class [" + getRootClass().getName() + "]");
				}
				return;
			}
			else {
				//属性是必选的, 则报错
				throw createNotWritablePropertyException(tokens.canonicalName);
			}
		}

		Object oldValue = null;
		try {
			//获取配置的原始值
			Object originalValue = pv.getValue();
			Object valueToApply = originalValue;
			//判断属性值是否需要转换, 如需要
			if (!Boolean.FALSE.equals(pv.conversionNecessary)) {
				//配置的值已经转换
				if (pv.isConverted()) {
					//获取转换的值
					valueToApply = pv.getConvertedValue();
				}
				else {
					//如果要记录旧值且 属性可读
					if (isExtractOldValueForEditor() && ph.isReadable()) {
						try {
							//获取属性原来的值
							oldValue = ph.getValue();
						}
						catch (Exception ex) {
							if (ex instanceof PrivilegedActionException) {
								ex = ((PrivilegedActionException) ex).getException();
							}
							if (logger.isDebugEnabled()) {
								logger.debug("Could not read previous value of property '" +
										this.nestedPath + tokens.canonicalName + "'", ex);
							}
						}
					}
					//将配置的值转换成属性的类型
					valueToApply = convertForProperty(
							tokens.canonicalName, oldValue, originalValue, ph.toTypeDescriptor());
				}
				//如果转换后的值和原值不一样, 则设置需要转换
				//todo wolfleong 不懂这里的作用
				pv.getOriginalPropertyValue().conversionNecessary = (valueToApply != originalValue);
			}
			//将最终值设置到属性中
			ph.setValue(valueToApply);
		}
		catch (TypeMismatchException ex) {
			throw ex;
		}
		catch (InvocationTargetException ex) {
			PropertyChangeEvent propertyChangeEvent = new PropertyChangeEvent(
					getRootInstance(), this.nestedPath + tokens.canonicalName, oldValue, pv.getValue());
			if (ex.getTargetException() instanceof ClassCastException) {
				throw new TypeMismatchException(propertyChangeEvent, ph.getPropertyType(), ex.getTargetException());
			}
			else {
				Throwable cause = ex.getTargetException();
				if (cause instanceof UndeclaredThrowableException) {
					// May happen e.g. with Groovy-generated methods
					cause = cause.getCause();
				}
				throw new MethodInvocationException(propertyChangeEvent, cause);
			}
		}
		catch (Exception ex) {
			PropertyChangeEvent pce = new PropertyChangeEvent(
					getRootInstance(), this.nestedPath + tokens.canonicalName, oldValue, pv.getValue());
			throw new MethodInvocationException(pce, ex);
		}
	}

	@Override
	@Nullable
	public Class<?> getPropertyType(String propertyName) throws BeansException {
		try {
			PropertyHandler ph = getPropertyHandler(propertyName);
			if (ph != null) {
				return ph.getPropertyType();
			}
			else {
				// Maybe an indexed/mapped property...
				Object value = getPropertyValue(propertyName);
				if (value != null) {
					return value.getClass();
				}
				// Check to see if there is a custom editor,
				// which might give an indication on the desired target type.
				Class<?> editorType = guessPropertyTypeFromEditors(propertyName);
				if (editorType != null) {
					return editorType;
				}
			}
		}
		catch (InvalidPropertyException ex) {
			// Consider as not determinable.
		}
		return null;
	}

	@Override
	@Nullable
	public TypeDescriptor getPropertyTypeDescriptor(String propertyName) throws BeansException {
		try {
			//获取当前属性的访问器
			AbstractNestablePropertyAccessor nestedPa = getPropertyAccessorForPropertyPath(propertyName);
			//获取嵌套路径的最后属性名
			String finalPath = getFinalPath(nestedPa, propertyName);
			//获取 PropertyTokenHolder
			PropertyTokenHolder tokens = getPropertyNameTokens(finalPath);
			//获取 PropertyHandler
			PropertyHandler ph = nestedPa.getLocalPropertyHandler(tokens.actualName);
			//如果 ph 不为 null
			if (ph != null) {
				//如果 keys 不为 null, 则表示是集合或Map
				if (tokens.keys != null) {
					//ph 可读或可写
					if (ph.isReadable() || ph.isWritable()) {
						//获取最后嵌套的类型描述器
						return ph.nested(tokens.keys.length);
					}
				}
				//非集合或Map
				else {
					//可读或或写
					if (ph.isReadable() || ph.isWritable()) {
						//直接返回类型描述器
						return ph.toTypeDescriptor();
					}
				}
			}
		}
		catch (InvalidPropertyException ex) {
			// Consider as not determinable.
		}
		//默认返回 null
		return null;
	}

	@Override
	public boolean isReadableProperty(String propertyName) {
		try {
			PropertyHandler ph = getPropertyHandler(propertyName);
			if (ph != null) {
				return ph.isReadable();
			}
			else {
				// Maybe an indexed/mapped property...
				getPropertyValue(propertyName);
				return true;
			}
		}
		catch (InvalidPropertyException ex) {
			// Cannot be evaluated, so can't be readable.
		}
		return false;
	}

	@Override
	public boolean isWritableProperty(String propertyName) {
		try {
			PropertyHandler ph = getPropertyHandler(propertyName);
			if (ph != null) {
				return ph.isWritable();
			}
			else {
				// Maybe an indexed/mapped property...
				getPropertyValue(propertyName);
				return true;
			}
		}
		catch (InvalidPropertyException ex) {
			// Cannot be evaluated, so can't be writable.
		}
		return false;
	}

	@Nullable
	private Object convertIfNecessary(@Nullable String propertyName, @Nullable Object oldValue,
			@Nullable Object newValue, @Nullable Class<?> requiredType, @Nullable TypeDescriptor td)
			throws TypeMismatchException {

		Assert.state(this.typeConverterDelegate != null, "No TypeConverterDelegate");
		try {
			return this.typeConverterDelegate.convertIfNecessary(propertyName, oldValue, newValue, requiredType, td);
		}
		catch (ConverterNotFoundException | IllegalStateException ex) {
			PropertyChangeEvent pce =
					new PropertyChangeEvent(getRootInstance(), this.nestedPath + propertyName, oldValue, newValue);
			throw new ConversionNotSupportedException(pce, requiredType, ex);
		}
		catch (ConversionException | IllegalArgumentException ex) {
			PropertyChangeEvent pce =
					new PropertyChangeEvent(getRootInstance(), this.nestedPath + propertyName, oldValue, newValue);
			throw new TypeMismatchException(pce, requiredType, ex);
		}
	}

	@Nullable
	protected Object convertForProperty(
			String propertyName, @Nullable Object oldValue, @Nullable Object newValue, TypeDescriptor td)
			throws TypeMismatchException {

		return convertIfNecessary(propertyName, oldValue, newValue, td.getType(), td);
	}

	@Override
	@Nullable
	public Object getPropertyValue(String propertyName) throws BeansException {
		AbstractNestablePropertyAccessor nestedPa = getPropertyAccessorForPropertyPath(propertyName);
		PropertyTokenHolder tokens = getPropertyNameTokens(getFinalPath(nestedPa, propertyName));
		return nestedPa.getPropertyValue(tokens);
	}

	/**
	 * 根据 PropertyTokenHolder 获取属性的值
	 */
	@SuppressWarnings("unchecked")
	@Nullable
	protected Object getPropertyValue(PropertyTokenHolder tokens) throws BeansException {
		//获取规范的属性名
		String propertyName = tokens.canonicalName;
		//获取真正的属性名
		String actualName = tokens.actualName;
		//根据实际属性名获取 PropertyHandler
		PropertyHandler ph = getLocalPropertyHandler(actualName);
		// ph == null
		//如果获取的 ph 为 null 或 属性不可以读取, 则抛异常
		if (ph == null || !ph.isReadable()) {
			throw new NotReadablePropertyException(getRootClass(), this.nestedPath + propertyName);
		}
		try {
			//获取属性值
			Object value = ph.getValue();
			//如果索引 key 不为 null
			if (tokens.keys != null) {
				//属性值为 null
				if (value == null) {
					//是否设置默认值
					if (isAutoGrowNestedPaths()) {
						//设置默认值
						value = setDefaultValue(new PropertyTokenHolder(tokens.actualName));
					}
					else {
						throw new NullValueInNestedPathException(getRootClass(), this.nestedPath + propertyName,
								"Cannot access indexed value of property referenced in indexed " +
										"property path '" + propertyName + "': returned null");
					}
				}
				StringBuilder indexedPropertyName = new StringBuilder(tokens.actualName);
				// apply indexes and map keys
				for (int i = 0; i < tokens.keys.length; i++) {
					String key = tokens.keys[i];
					//如果 value 是 null, 则报异常
					if (value == null) {
						throw new NullValueInNestedPathException(getRootClass(), this.nestedPath + propertyName,
								"Cannot access indexed value of property referenced in indexed " +
										"property path '" + propertyName + "': returned null");
					}
					//如果 value 是个数组
					else if (value.getClass().isArray()) {
						//将 key 转换成索引
						int index = Integer.parseInt(key);
						//如果 index 超过原数组长度, 可能扩容
						value = growArrayIfNecessary(value, index, indexedPropertyName.toString());
						//获取索引的值
						value = Array.get(value, index);
					}
					//如果 value 是列表
					else if (value instanceof List) {
						//获取索引
						int index = Integer.parseInt(key);
						//强转
						List<Object> list = (List<Object>) value;
						//如果可能, 扩容
						growCollectionIfNecessary(list, index, indexedPropertyName.toString(), ph, i + 1);
						//根据位置获取值
						value = list.get(index);
					}
					//如果值是 Set
					else if (value instanceof Set) {
						//强转
						// Apply index to Iterator in case of a Set.
						Set<Object> set = (Set<Object>) value;
						//解析索引位置
						int index = Integer.parseInt(key);
						//如果索引小于0或大于最大长度, 都报异常, Set 是没办法扩容的
						if (index < 0 || index >= set.size()) {
							throw new InvalidPropertyException(getRootClass(), this.nestedPath + propertyName,
									"Cannot get element with index " + index + " from Set of size " +
											set.size() + ", accessed using property path '" + propertyName + "'");
						}
						//获取迭代器
						Iterator<Object> it = set.iterator();
						//遍历
						for (int j = 0; it.hasNext(); j++) {
							//获取对象
							Object elem = it.next();
							//如果索引对象一样取值
							if (j == index) {
								value = elem;
								break;
							}
						}
					}
					//如果 value 是 Map
					else if (value instanceof Map) {
						//强转
						Map<Object, Object> map = (Map<Object, Object>) value;
						//获取 Map 的 key 类型
						Class<?> mapKeyType = ph.getResolvableType().getNested(i + 1).asMap().resolveGeneric(0);
						// IMPORTANT: Do not pass full property name in here - property editors
						// must not kick in for map keys but rather only for map values.
						//创建 TypeDescriptor
						TypeDescriptor typeDescriptor = TypeDescriptor.valueOf(mapKeyType);
						//将 key 转成 Map 的 key 类型
						Object convertedMapKey = convertIfNecessary(null, null, key, mapKeyType, typeDescriptor);
						//获取给定 key 的值
						value = map.get(convertedMapKey);
					}
					else {
						throw new InvalidPropertyException(getRootClass(), this.nestedPath + propertyName,
								"Property referenced in indexed property path '" + propertyName +
										"' is neither an array nor a List nor a Set nor a Map; returned value was [" + value + "]");
					}
					//拼接属性名
					indexedPropertyName.append(PROPERTY_KEY_PREFIX).append(key).append(PROPERTY_KEY_SUFFIX);
				}
			}
			return value;
		}
		catch (IndexOutOfBoundsException ex) {
			throw new InvalidPropertyException(getRootClass(), this.nestedPath + propertyName,
					"Index of out of bounds in property path '" + propertyName + "'", ex);
		}
		catch (NumberFormatException | TypeMismatchException ex) {
			throw new InvalidPropertyException(getRootClass(), this.nestedPath + propertyName,
					"Invalid index in property path '" + propertyName + "'", ex);
		}
		catch (InvocationTargetException ex) {
			throw new InvalidPropertyException(getRootClass(), this.nestedPath + propertyName,
					"Getter for property '" + actualName + "' threw exception", ex);
		}
		catch (Exception ex) {
			throw new InvalidPropertyException(getRootClass(), this.nestedPath + propertyName,
					"Illegal attempt to get property '" + actualName + "' threw exception", ex);
		}
	}


	/**
	 * Return the {@link PropertyHandler} for the specified {@code propertyName}, navigating
	 * if necessary. Return {@code null} if not found rather than throwing an exception.
	 * @param propertyName the property to obtain the descriptor for
	 * @return the property descriptor for the specified property,
	 * or {@code null} if not found
	 * @throws BeansException in case of introspection failure
	 */
	@Nullable
	protected PropertyHandler getPropertyHandler(String propertyName) throws BeansException {
		Assert.notNull(propertyName, "Property name must not be null");
		AbstractNestablePropertyAccessor nestedPa = getPropertyAccessorForPropertyPath(propertyName);
		return nestedPa.getLocalPropertyHandler(getFinalPath(nestedPa, propertyName));
	}

	/**
	 * Return a {@link PropertyHandler} for the specified local {@code propertyName}.
	 * Only used to reach a property available in the current context.
	 * @param propertyName the name of a local property
	 * @return the handler for that property, or {@code null} if it has not been found
	 */
	@Nullable
	protected abstract PropertyHandler getLocalPropertyHandler(String propertyName);

	/**
	 * Create a new nested property accessor instance.
	 * Can be overridden in subclasses to create a PropertyAccessor subclass.
	 * @param object object wrapped by this PropertyAccessor
	 * @param nestedPath the nested path of the object
	 * @return the nested PropertyAccessor instance
	 */
	protected abstract AbstractNestablePropertyAccessor newNestedPropertyAccessor(Object object, String nestedPath);

	/**
	 * Create a {@link NotWritablePropertyException} for the specified property.
	 */
	protected abstract NotWritablePropertyException createNotWritablePropertyException(String propertyName);


	private Object growArrayIfNecessary(Object array, int index, String name) {
		//如果不设置默认值, 则直接返回当前数组
		if (!isAutoGrowNestedPaths()) {
			return array;
		}
		//获取数组的长度
		int length = Array.getLength(array);
		//如果索引大于等于当前数组长度且小于集合允许增长的长度限制
		if (index >= length && index < this.autoGrowCollectionLimit) {
			//获取数组组件类型
			Class<?> componentType = array.getClass().getComponentType();
			//创建新数组
			Object newArray = Array.newInstance(componentType, index + 1);
			//复制当前数组的值到新的数组
			System.arraycopy(array, 0, newArray, 0, length);
			//给新数组扩容后面的位置添加默认值
			for (int i = length; i < Array.getLength(newArray); i++) {
				Array.set(newArray, i, newValue(componentType, null, name));
			}
			//设置新的数组到属性中
			setPropertyValue(name, newArray);
			//根据属性名获取此默认值
			Object defaultValue = getPropertyValue(name);
			//判断默认值不为 null
			Assert.state(defaultValue != null, "Default value must not be null");
			//返回默认值
			return defaultValue;
		}
		//没超过原数组长度或超过集合长度限制, 则返回原数组
		else {
			return array;
		}
	}

	private void growCollectionIfNecessary(Collection<Object> collection, int index, String name,
			PropertyHandler ph, int nestingLevel) {

		//不自动填充
		if (!isAutoGrowNestedPaths()) {
			return;
		}
		//集合长度
		int size = collection.size();
		//索引超过长度且没超出限制
		if (index >= size && index < this.autoGrowCollectionLimit) {
			//解析集合元素的类型
			Class<?> elementType = ph.getResolvableType().getNested(nestingLevel).asCollection().resolveGeneric();
			//如果集合类型不为 null
			if (elementType != null) {
				//遍历
				for (int i = collection.size(); i < index + 1; i++) {
					//填充扩容后所有位置默值
					collection.add(newValue(elementType, null, name));
				}
			}
		}
	}

	/**
	 * 获取最后一个属性名, 如: director.info.name 返回 name
	 * Get the last component of the path. Also works if not nested.
	 * @param pa property accessor to work on
	 * @param nestedPath property path we know is nested
	 * @return last component of the path (the property on the target bean)
	 */
	protected String getFinalPath(AbstractNestablePropertyAccessor pa, String nestedPath) {
		//如果属性访问器跟当前的一样, 直接返回
		if (pa == this) {
			return nestedPath;
		}
		//截取最后访问器
		return nestedPath.substring(PropertyAccessorUtils.getLastNestedPropertySeparatorIndex(nestedPath) + 1);
	}

	/**
	 * 根据属性(propertyPath)获取所在 bean 的包装对象 beanWrapper。
	 * 如果是类似 director.info.name 的嵌套属性，则需要递归获取。真正获取指定属性的包装对象则由方法 getNestedPropertyAccessor 完成
	 * - 2. 递归处理嵌套属性
	 * - 2.1 先获取 director 属性所在类的 rootBeanWrapper
	 * - 2.2 再获取 info 属性所在类的 directorBeanWrapper
	 * - 2.3 依此类推，获取最后一个属性 name 属性所在类的 infoBeanWrapper
	 * Recursively navigate to return a property accessor for the nested property path.
	 * @param propertyPath property path, which may be nested
	 * @return a property accessor for the target bean
	 */
	@SuppressWarnings("unchecked")  // avoid nested generic
	protected AbstractNestablePropertyAccessor getPropertyAccessorForPropertyPath(String propertyPath) {
		//获取第一个点之前的属性部分。eg: director.info.name 返回 department
		int pos = PropertyAccessorUtils.getFirstNestedPropertySeparatorIndex(propertyPath);
		//如果索引存在, 则表示有嵌套属性
		// Handle nested properties recursively.
		if (pos > -1) {
			//截取第一个嵌套属性
			String nestedProperty = propertyPath.substring(0, pos);
			//截取剩下的 path
			String nestedPath = propertyPath.substring(pos + 1);
			//获取当前属性的属性访问器
			AbstractNestablePropertyAccessor nestedPa = getNestedPropertyAccessor(nestedProperty);
			//从这个属性访问器中获取剩下的 Path 的属性访问器
			return nestedPa.getPropertyAccessorForPropertyPath(nestedPath);
		}
		//如果没有嵌套属性, 则直接返回当前对象
		else {
			return this;
		}
	}

	/**
	 * Retrieve a Property accessor for the given nested property.
	 * Create a new one if not found in the cache.
	 * <p>Note: Caching nested PropertyAccessors is necessary now,
	 * to keep registered custom editors for nested properties.
	 * @param nestedProperty property to create the PropertyAccessor for
	 * @return the PropertyAccessor instance, either cached or newly created
	 */
	private AbstractNestablePropertyAccessor getNestedPropertyAccessor(String nestedProperty) {
		//如果缓存没初始化, 则初始化
		if (this.nestedPropertyAccessors == null) {
			this.nestedPropertyAccessors = new HashMap<>();
		}
		// Get value of bean property.
		//获取属性对应的 token 值，主要用于解析 attrs['key'][0] 这样 Map/Array/Collection 循环嵌套的属性
		PropertyTokenHolder tokens = getPropertyNameTokens(nestedProperty);
		String canonicalName = tokens.canonicalName;
		//根据 PropertyTokenHolder , 获取属性值
		Object value = getPropertyValue(tokens);
		//如果 value 值是 null 或者值是 Optional 类型但不存在值
		if (value == null || (value instanceof Optional && !((Optional<?>) value).isPresent())) {
			//判断是否自动设置默认值
			if (isAutoGrowNestedPaths()) {
				//设置默认值
				value = setDefaultValue(tokens);
			}
			else {
				//不设置默认值, 则报错
				throw new NullValueInNestedPathException(getRootClass(), this.nestedPath + canonicalName);
			}
		}

		//根据标准的属性表达式从缓存中获取 AbstractNestablePropertyAccessor
		// Lookup cached sub-PropertyAccessor, create new one if not found.
		AbstractNestablePropertyAccessor nestedPa = this.nestedPropertyAccessors.get(canonicalName);
		//如果没有获取到或 nestedPa 的包装实体不是属性的值
		if (nestedPa == null || nestedPa.getWrappedInstance() != ObjectUtils.unwrapOptional(value)) {
			if (logger.isTraceEnabled()) {
				logger.trace("Creating new nested " + getClass().getSimpleName() + " for property '" + canonicalName + "'");
			}
			//创建一个, 并前设置 nestedPath
			nestedPa = newNestedPropertyAccessor(value, this.nestedPath + canonicalName + NESTED_PROPERTY_SEPARATOR);
			// Inherit all type-specific PropertyEditors.
			//复制默认的属性编辑器
			copyDefaultEditorsTo(nestedPa);
			//复制自定义编辑器
			copyCustomEditorsTo(nestedPa, canonicalName);
			//添加到缓存
			this.nestedPropertyAccessors.put(canonicalName, nestedPa);
		}
		else {
			if (logger.isTraceEnabled()) {
				logger.trace("Using cached nested property accessor for property '" + canonicalName + "'");
			}
		}
		//返回
		return nestedPa;
	}

	/**
	 * 设置指定属性的默认值
	 */
	private Object setDefaultValue(PropertyTokenHolder tokens) {
		//根据 PropertyTokenHolder 创建默认的 PropertyValue
		PropertyValue pv = createDefaultPropertyValue(tokens);
		//设置属性值
		setPropertyValue(tokens, pv);
		//获取默认值
		Object defaultValue = getPropertyValue(tokens);
		Assert.state(defaultValue != null, "Default value must not be null");
		//返回
		return defaultValue;
	}

	/**
	 * 创建给定 PropertyTokenHolder 的默认属性对象 PropertyValue
	 */
	private PropertyValue createDefaultPropertyValue(PropertyTokenHolder tokens) {
		//获取给定属性名的类型描述器
		TypeDescriptor desc = getPropertyTypeDescriptor(tokens.canonicalName);
		//如果属性描述器为 null 则报错
		if (desc == null) {
			throw new NullValueInNestedPathException(getRootClass(), this.nestedPath + tokens.canonicalName,
					"Could not determine property type for auto-growing a default value");
		}
		//根据类型创建默认值
		Object defaultValue = newValue(desc.getType(), desc, tokens.canonicalName);
		//创建 PropertyValue
		return new PropertyValue(tokens.canonicalName, defaultValue);
	}

	/**
	 * 根据给定类型和名称, 创建默认值
	 */
	private Object newValue(Class<?> type, @Nullable TypeDescriptor desc, String name) {
		try {
			//如果类型是数组
			if (type.isArray()) {
				//获取数组的元素类型
				Class<?> componentType = type.getComponentType();
				// TODO - only handles 2-dimensional arrays
				//如元素也是数组, 注意, 这里只支持二维数组
				if (componentType.isArray()) {
					//创建第一维数组对象
					Object array = Array.newInstance(componentType, 1);
					//创建第二维数组
					Array.set(array, 0, Array.newInstance(componentType.getComponentType(), 0));
					//返回
					return array;
				}
				else {
					//只是一维数组, 创建并返回
					return Array.newInstance(componentType, 0);
				}
			}
			//如果是集合类型
			else if (Collection.class.isAssignableFrom(type)) {
				//获取元素类型
				TypeDescriptor elementDesc = (desc != null ? desc.getElementTypeDescriptor() : null);
				//创建给定元素类型的集合
				return CollectionFactory.createCollection(type, (elementDesc != null ? elementDesc.getType() : null), 16);
			}
			//如果是 Map 类型
			else if (Map.class.isAssignableFrom(type)) {
				//获取 key 的类型描述器
				TypeDescriptor keyDesc = (desc != null ? desc.getMapKeyTypeDescriptor() : null);
				//根据 key 的类型创建一个 Map
				return CollectionFactory.createMap(type, (keyDesc != null ? keyDesc.getType() : null), 16);
			}
			//如果是其他对象
			else {
				//获取默认构造器
				Constructor<?> ctor = type.getDeclaredConstructor();
				//如果构造器是私有的, 则报错
				if (Modifier.isPrivate(ctor.getModifiers())) {
					throw new IllegalAccessException("Auto-growing not allowed with private constructor: " + ctor);
				}
				//创建
				return BeanUtils.instantiateClass(ctor);
			}
		}
		catch (Throwable ex) {
			throw new NullValueInNestedPathException(getRootClass(), this.nestedPath + name,
					"Could not instantiate property type [" + type.getName() + "] to auto-grow nested property path", ex);
		}
	}

	/**
	 * 解析给定属性, 封成 PropertyTokenHolder, 以属性名 attrs['key'][0] 为例
	 * Parse the given property name into the corresponding property name tokens.
	 * @param propertyName the property name to parse
	 * @return representation of the parsed property tokens
	 */
	private PropertyTokenHolder getPropertyNameTokens(String propertyName) {
		String actualName = null;
		//主要存在 [] 里面的值, 如: key , 0 等
		List<String> keys = new ArrayList<>(2);
		//寻找的索引默认为 0 , 从 0 开始查询
		int searchIndex = 0;
		//如果索引存在
		while (searchIndex != -1) {
			//以 searchIndex 为开始索引获取 '[' 字符的索引
			int keyStart = propertyName.indexOf(PROPERTY_KEY_PREFIX, searchIndex);
			//重置 searchIndex
			searchIndex = -1;
			//如果 key 的开始索引不为 -1
			if (keyStart != -1) {
				//获取 key 的结束索引, 也就是对应 ']' 的位置
				int keyEnd = getPropertyNameKeyEnd(propertyName, keyStart + PROPERTY_KEY_PREFIX.length());
				//如果 key 的结束索引存在
				if (keyEnd != -1) {
					//如果 actualName 不存在
					if (actualName == null) {
						//截取 '[' 前面的字符串做实际的属性名
						actualName = propertyName.substring(0, keyStart);
					}
					//获取 [key] 两个字符串的 key 值
					String key = propertyName.substring(keyStart + PROPERTY_KEY_PREFIX.length(), keyEnd);
					//如果 key 有单引号或双引用, 则去除, 如
					if (key.length() > 1 && (key.startsWith("'") && key.endsWith("'")) ||
							(key.startsWith("\"") && key.endsWith("\""))) {
						key = key.substring(1, key.length() - 1);
					}
					//记录到 keys 中
					keys.add(key);
					//获取 ']' 字符后面的索引, 查询下一个 []
					searchIndex = keyEnd + PROPERTY_KEY_SUFFIX.length();
				}
			}
		}
		//创建 PropertyTokenHolder , 如果 actualName 为 null, 则表示 propertyName 不存在 []
		PropertyTokenHolder tokens = new PropertyTokenHolder(actualName != null ? actualName : propertyName);
		//如果 keys 不为空
		if (!keys.isEmpty()) {
			//拼接 attrs[key][0]
			tokens.canonicalName += PROPERTY_KEY_PREFIX +
					StringUtils.collectionToDelimitedString(keys, PROPERTY_KEY_SUFFIX + PROPERTY_KEY_PREFIX) +
					PROPERTY_KEY_SUFFIX;
			//将列表变数组
			tokens.keys = StringUtils.toStringArray(keys);
		}
		//返回
		return tokens;
	}

	/**
	 * 获取给定属性名给定key的开始索引查找结束索引
	 */
	private int getPropertyNameKeyEnd(String propertyName, int startIndex) {
		//未关闭的前缀个数
		int unclosedPrefixes = 0;
		//属性字符长度
		int length = propertyName.length();
		//遍历每个字符
		for (int i = startIndex; i < length; i++) {
			switch (propertyName.charAt(i)) {
				//如果遇到 '[' , 未关闭个数加 1
				case PropertyAccessor.PROPERTY_KEY_PREFIX_CHAR:
					// The property name contains opening prefix(es)...
					unclosedPrefixes++;
					break;
					//如果遇到 ']'
				case PropertyAccessor.PROPERTY_KEY_SUFFIX_CHAR:
					//如果没有未闭的  '[' , 则直接返回此位置
					if (unclosedPrefixes == 0) {
						// No unclosed prefix(es) in the property name (left) ->
						// this is the suffix we are looking for.
						return i;
					}
					else {
						//抵消
						// This suffix does not close the initial prefix but rather
						// just one that occurred within the property name.
						unclosedPrefixes--;
					}
					break;
			}
		}
		return -1;
	}


	@Override
	public String toString() {
		String className = getClass().getName();
		if (this.wrappedObject == null) {
			return className + ": no wrapped object set";
		}
		return className + ": wrapping object [" + ObjectUtils.identityToString(this.wrappedObject) + ']';
	}


	/**
	 * 属性操作处理器
	 * A handler for a specific property.
	 */
	protected abstract static class PropertyHandler {

		private final Class<?> propertyType;

		private final boolean readable;

		private final boolean writable;

		public PropertyHandler(Class<?> propertyType, boolean readable, boolean writable) {
			this.propertyType = propertyType;
			this.readable = readable;
			this.writable = writable;
		}

		public Class<?> getPropertyType() {
			return this.propertyType;
		}

		public boolean isReadable() {
			return this.readable;
		}

		public boolean isWritable() {
			return this.writable;
		}

		/**
		 * 获取属性的类型描述器
		 */
		public abstract TypeDescriptor toTypeDescriptor();

		/**
		 * 获取属性的泛型类型包装
		 */
		public abstract ResolvableType getResolvableType();

		@Nullable
		public Class<?> getMapKeyType(int nestingLevel) {
			return getResolvableType().getNested(nestingLevel).asMap().resolveGeneric(0);
		}

		@Nullable
		public Class<?> getMapValueType(int nestingLevel) {
			return getResolvableType().getNested(nestingLevel).asMap().resolveGeneric(1);
		}

		@Nullable
		public Class<?> getCollectionType(int nestingLevel) {
			return getResolvableType().getNested(nestingLevel).asCollection().resolveGeneric();
		}

		/**
		 * 获取给定嵌套层级的泛型类型描述器
		 */
		@Nullable
		public abstract TypeDescriptor nested(int level);

		/**
		 * 获取属性的值
		 */
		@Nullable
		public abstract Object getValue() throws Exception;

		/**
		 * 设置属性值的 value
		 */
		public abstract void setValue(@Nullable Object value) throws Exception;
	}


	/**
	 * 用于解析嵌套属性名称，标识唯一的属性, attrs['key'][0] 这样的属性处理结果
	 * Holder class used to store property tokens.
	 */
	protected static class PropertyTokenHolder {

		public PropertyTokenHolder(String name) {
			this.actualName = name;
			this.canonicalName = name;
		}

		/**
		 * 对应 bean 中的属性名称，如嵌套属性 attrs['key'][0] 在 bean 中的属性名称为 attrs
		 */
		public String actualName;

		/**
		 * 将原始的嵌套属性处理成标准的 token，
		 * 如 attrs['key'][0] 处理成 attrs[key][0]
		 */
		public String canonicalName;

		/**
		 * 这个数组存放的是嵌套属性 [] 中的内容，如 attrs['key'][0] 处理成 ["key", "0"]
		 */
		@Nullable
		public String[] keys;
	}

}
