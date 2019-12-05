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

import java.beans.PropertyEditor;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.core.CollectionFactory;
import org.springframework.core.convert.ConversionFailedException;
import org.springframework.core.convert.ConversionService;
import org.springframework.core.convert.TypeDescriptor;
import org.springframework.lang.Nullable;
import org.springframework.util.ClassUtils;
import org.springframework.util.NumberUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StringUtils;

/**
 * 类型转换的委托实现帮助类
 * Internal helper class for converting property values to target types.
 *
 * <p>Works on a given {@link PropertyEditorRegistrySupport} instance.
 * Used as a delegate by {@link BeanWrapperImpl} and {@link SimpleTypeConverter}.
 *
 * @author Juergen Hoeller
 * @author Rob Harrop
 * @author Dave Syer
 * @since 2.0
 * @see BeanWrapperImpl
 * @see SimpleTypeConverter
 */
class TypeConverterDelegate {

	private static final Log logger = LogFactory.getLog(TypeConverterDelegate.class);

	/**
	 * 属性注册器
	 */
	private final PropertyEditorRegistrySupport propertyEditorRegistry;

	/**
	 * 主要用于获取类加载器
	 */
	@Nullable
	private final Object targetObject;


	/**
	 * Create a new TypeConverterDelegate for the given editor registry.
	 * @param propertyEditorRegistry the editor registry to use
	 */
	public TypeConverterDelegate(PropertyEditorRegistrySupport propertyEditorRegistry) {
		this(propertyEditorRegistry, null);
	}

	/**
	 * Create a new TypeConverterDelegate for the given editor registry and bean instance.
	 * @param propertyEditorRegistry the editor registry to use
	 * @param targetObject the target object to work on (as context that can be passed to editors)
	 */
	public TypeConverterDelegate(PropertyEditorRegistrySupport propertyEditorRegistry, @Nullable Object targetObject) {
		this.propertyEditorRegistry = propertyEditorRegistry;
		this.targetObject = targetObject;
	}


	/**
	 * Convert the value to the required type for the specified property.
	 * @param propertyName name of the property
	 * @param oldValue the previous value, if available (may be {@code null})
	 * @param newValue the proposed new value
	 * @param requiredType the type we must convert to
	 * (or {@code null} if not known, for example in case of a collection element)
	 * @return the new value, possibly the result of type conversion
	 * @throws IllegalArgumentException if type conversion failed
	 */
	@Nullable
	public <T> T convertIfNecessary(@Nullable String propertyName, @Nullable Object oldValue,
			Object newValue, @Nullable Class<T> requiredType) throws IllegalArgumentException {

		return convertIfNecessary(propertyName, oldValue, newValue, requiredType, TypeDescriptor.valueOf(requiredType));
	}

	/**
	 * 这个方法的逻辑写得真上乱, 看了半天, 我太难了, 总结一下这里的转换逻辑
	 * - 先用 ConversionService 处理转换, ConversionService 基本是支持任何类型的, 包括 Collection, Map 等, 如果能转就直接返回
	 * - 再用 PropertyEditor 来进行转换, 先尝试用自定义, 再用默认的属性编辑器
	 * - 最后, 处理转换后的类型与给定的类型, 主要对集合元素遍历转换, 枚举转换等, 因为这些转换有可能 PropertyEditor 处理不了
	 * Convert the value to the required type (if necessary from a String),
	 * for the specified property.
	 * @param propertyName name of the property
	 * @param oldValue the previous value, if available (may be {@code null})
	 * @param newValue the proposed new value
	 * @param requiredType the type we must convert to
	 * (or {@code null} if not known, for example in case of a collection element)
	 * @param typeDescriptor the descriptor for the target property or field
	 * @return the new value, possibly the result of type conversion
	 * @throws IllegalArgumentException if type conversion failed
	 */
	@SuppressWarnings("unchecked")
	@Nullable
	public <T> T convertIfNecessary(@Nullable String propertyName, @Nullable Object oldValue, @Nullable Object newValue,
			@Nullable Class<T> requiredType, @Nullable TypeDescriptor typeDescriptor) throws IllegalArgumentException {

		//根据类型和属性名, 获取自定义的 PropertyEditor
		// Custom editor for this type?
		PropertyEditor editor = this.propertyEditorRegistry.findCustomEditor(requiredType, propertyName);

		//转换服务的转换异常
		ConversionFailedException conversionAttemptEx = null;

		//获取转换服务
		// No custom editor but custom ConversionService specified?
		ConversionService conversionService = this.propertyEditorRegistry.getConversionService();
		//首先尝试用转换服务进行转换
		//如果属性编辑器为 null 且转换服务不为 null 且 newValue 不为 null 且目标类型描述器不为 null
		if (editor == null && conversionService != null && newValue != null && typeDescriptor != null) {
			//获取值的 TypeDescriptor
			TypeDescriptor sourceTypeDesc = TypeDescriptor.forObject(newValue);
			//判断是否可以转换
			if (conversionService.canConvert(sourceTypeDesc, typeDescriptor)) {
				try {
					//直接转换返回
					return (T) conversionService.convert(newValue, sourceTypeDesc, typeDescriptor);
				}
				catch (ConversionFailedException ex) {
					// fallback to default conversion logic below
					conversionAttemptEx = ex;
				}
			}
		}

		//转换器没转换成功, 则执行下面的逻辑
		Object convertedValue = newValue;

		//如果属性编辑器不为 null
		//或 目标类型不为 null 且值的类型与目标类型不匹配
		// Value not of required type?
		if (editor != null || (requiredType != null && !ClassUtils.isAssignableValue(requiredType, convertedValue))) {
			//如果类型描述器不为 null 且结果类型不为 null 且结果类型是集合类型且要转换的值是字符串, 也就是要将字符串变成集合类型
			if (typeDescriptor != null && requiredType != null && Collection.class.isAssignableFrom(requiredType) &&
					convertedValue instanceof String) {
				//从类型描述器中获取元素的泛型描述器
				TypeDescriptor elementTypeDesc = typeDescriptor.getElementTypeDescriptor();
				//如果元素类型不为 null
				if (elementTypeDesc != null) {
					//获取元素类型
					Class<?> elementType = elementTypeDesc.getType();
					//如果元素类型是 Class 或 Enum
					if (Class.class == elementType || Enum.class.isAssignableFrom(elementType)) {
						//逗号分割变字符串数组
						convertedValue = StringUtils.commaDelimitedListToStringArray((String) convertedValue);
					}
				}
			}
			//如果属性编辑器为 null
			if (editor == null) {
				//根据类型查询默认属性编辑器
				editor = findDefaultEditor(requiredType);
			}
			//执行用属性编辑器执行转换, 这里基本只能处理字符串转其他对象, 一般不支持 Collection, Map, Array,
			convertedValue = doConvertValue(oldValue, convertedValue, requiredType, editor);
		}

		//是否是标准的转换, Collection, Map, Array2One, Enum , Number
		boolean standardConversion = false;

		//如果结果类型不为 null
		if (requiredType != null) {
			// Try to apply some standard type conversion rules if appropriate.

			//如果已经转换的值不为 null
			if (convertedValue != null) {
				//如果结果类型是 Object , 则直接返回
				if (Object.class == requiredType) {
					return (T) convertedValue;
				}
				//如果结果类型是个数组
				else if (requiredType.isArray()) {
					//如果转换后的值是字符串且 requiredType 是枚举数组
					// Array required -> apply appropriate conversion of elements.
					if (convertedValue instanceof String && Enum.class.isAssignableFrom(requiredType.getComponentType())) {
						//强转字符串, 逗号切割成字符串数组
						convertedValue = StringUtils.commaDelimitedListToStringArray((String) convertedValue);
					}
					//转换成枚举数组
					return (T) convertToTypedArray(convertedValue, propertyName, requiredType.getComponentType());
				}
				//如果转换的值是集合类型
				else if (convertedValue instanceof Collection) {
					//转换集合元素的类型
					// Convert elements to target type, if determined.
					convertedValue = convertToTypedCollection(
							(Collection<?>) convertedValue, propertyName, requiredType, typeDescriptor);
					standardConversion = true;
				}
				//如果结果值是 Map
				else if (convertedValue instanceof Map) {
					//转换 Map 中 kv 的类型
					// Convert keys and values to respective target type, if determined.
					convertedValue = convertToTypedMap(
							(Map<?, ?>) convertedValue, propertyName, requiredType, typeDescriptor);
					standardConversion = true;
				}
				//如果结果值类型是数组且只有一个值
				if (convertedValue.getClass().isArray() && Array.getLength(convertedValue) == 1) {
					//获取数组的第一个值作为转换过的值
					convertedValue = Array.get(convertedValue, 0);
					standardConversion = true;
				}
				//如果结果类型是字符串, 值类型是基本类型或基本类型的包装类型
				if (String.class == requiredType && ClassUtils.isPrimitiveOrWrapper(convertedValue.getClass())) {
					//直接变字符串返回
					// We can stringify any primitive value...
					return (T) convertedValue.toString();
				}
				//如果 convertedValue 是字符串类型且结果值非结果类型
				else if (convertedValue instanceof String && !requiredType.isInstance(convertedValue)) {
					//如果转换异常不为 null 且结果类型非接口非枚举
					if (conversionAttemptEx == null && !requiredType.isInterface() && !requiredType.isEnum()) {
						try {
							//尝试获取结果类型带一个字符串参数的构造器
							Constructor<T> strCtor = requiredType.getConstructor(String.class);
							//初始化处理并返回
							return BeanUtils.instantiateClass(strCtor, convertedValue);
						}
						catch (NoSuchMethodException ex) {
							// proceed with field lookup
							if (logger.isTraceEnabled()) {
								logger.trace("No String constructor found on type [" + requiredType.getName() + "]", ex);
							}
						}
						catch (Exception ex) {
							if (logger.isDebugEnabled()) {
								logger.debug("Construction via String failed for type [" + requiredType.getName() + "]", ex);
							}
						}
					}
					//将值去前后空格
					String trimmedValue = ((String) convertedValue).trim();
					//如果结果类型是枚举且字符串是空串, 则返回 null
					if (requiredType.isEnum() && trimmedValue.isEmpty()) {
						// It's an empty enum identifier: reset the enum value to null.
						return null;
					}
					convertedValue = attemptToConvertStringToEnum(requiredType, trimmedValue, convertedValue);
					standardConversion = true;
				}
				//如果结果值是数字类型, 且结果类型也是数字类型
				else if (convertedValue instanceof Number && Number.class.isAssignableFrom(requiredType)) {
					//将数字转换成给定的数组类型
					convertedValue = NumberUtils.convertNumberToTargetClass(
							(Number) convertedValue, (Class<Number>) requiredType);
					standardConversion = true;
				}
			}
			//结果值为 null 的情况下
			else {
				// convertedValue == null
				//处理 Optional 类型
				if (requiredType == Optional.class) {
					convertedValue = Optional.empty();
				}
			}

			//如果值不能转换成给定的类型
			if (!ClassUtils.isAssignableValue(requiredType, convertedValue)) {
				//有转换异常的话, 直接抛出
				if (conversionAttemptEx != null) {
					// Original exception from former ConversionService call above...
					throw conversionAttemptEx;
				}
				//如果转换服务不为 null, 且类型参数不为 null, 则用转换服务进行转换
				else if (conversionService != null && typeDescriptor != null) {
					// ConversionService not tried before, probably custom editor found
					// but editor couldn't produce the required type...
					TypeDescriptor sourceTypeDesc = TypeDescriptor.forObject(newValue);
					if (conversionService.canConvert(sourceTypeDesc, typeDescriptor)) {
						return (T) conversionService.convert(newValue, sourceTypeDesc, typeDescriptor);
					}
				}
				//最后没得处理, 只能给异常

				// Definitely doesn't match: throw IllegalArgumentException/IllegalStateException
				StringBuilder msg = new StringBuilder();
				msg.append("Cannot convert value of type '").append(ClassUtils.getDescriptiveType(newValue));
				msg.append("' to required type '").append(ClassUtils.getQualifiedName(requiredType)).append("'");
				if (propertyName != null) {
					msg.append(" for property '").append(propertyName).append("'");
				}
				if (editor != null) {
					msg.append(": PropertyEditor [").append(editor.getClass().getName()).append(
							"] returned inappropriate value of type '").append(
							ClassUtils.getDescriptiveType(convertedValue)).append("'");
					throw new IllegalArgumentException(msg.toString());
				}
				else {
					msg.append(": no matching editors or conversion strategy found");
					throw new IllegalStateException(msg.toString());
				}
			}
		}

		//值的类型是返回的类型, 但有转换异常
		if (conversionAttemptEx != null) {
			//这里表示没转换成功
			//属性编辑器为 null 且非标准转换且结果类型不为 null 且结果类型不为 Object
			if (editor == null && !standardConversion && requiredType != null && Object.class != requiredType) {
				throw conversionAttemptEx;
			}
			//进入这里, 表示转换服务异常但属性编辑器转换正常, 打 debug 日志
			logger.debug("Original ConversionService attempt failed - ignored since " +
					"PropertyEditor based conversion eventually succeeded", conversionAttemptEx);
		}

		//返回转换的结果
		return (T) convertedValue;
	}

	/**
	 * 将字符串转换枚举类型
	 */
	private Object attemptToConvertStringToEnum(Class<?> requiredType, String trimmedValue, Object currentConvertedValue) {
		//记录未转换的值
		Object convertedValue = currentConvertedValue;

		//如果结果类型是枚举且 targetObject 不为 null
		if (Enum.class == requiredType && this.targetObject != null) {
			// target type is declared as raw enum, treat the trimmed value as <enum.fqn>.FIELD_NAME
			//获取 . 索引位置
			int index = trimmedValue.lastIndexOf('.');
			//如果有
			if (index > - 1) {
				//截取枚举类
				String enumType = trimmedValue.substring(0, index);
				//截取枚举
				String fieldName = trimmedValue.substring(index + 1);
				//获取类加载器
				ClassLoader cl = this.targetObject.getClass().getClassLoader();
				try {
					//加载枚举类
					Class<?> enumValueType = ClassUtils.forName(enumType, cl);
					//获取枚举字段反射
					Field enumField = enumValueType.getField(fieldName);
					//获取枚举值
					convertedValue = enumField.get(null);
				}
				catch (ClassNotFoundException ex) {
					if (logger.isTraceEnabled()) {
						logger.trace("Enum class [" + enumType + "] cannot be loaded", ex);
					}
				}
				catch (Throwable ex) {
					if (logger.isTraceEnabled()) {
						logger.trace("Field [" + fieldName + "] isn't an enum value for type [" + enumType + "]", ex);
					}
				}
			}
		}

		//如果结果值没有改变
		if (convertedValue == currentConvertedValue) {
			// Try field lookup as fallback: for JDK 1.5 enum or custom enum
			// with values defined as static fields. Resulting value still needs
			// to be checked, hence we don't return it right away.
			try {
				//用 requiredType 获取字段反射
				Field enumField = requiredType.getField(trimmedValue);
				//设置访问权限
				ReflectionUtils.makeAccessible(enumField);
				//获取枚举值
				convertedValue = enumField.get(null);
			}
			catch (Throwable ex) {
				if (logger.isTraceEnabled()) {
					logger.trace("Field [" + convertedValue + "] isn't an enum value", ex);
				}
			}
		}

		//返回枚举值
		return convertedValue;
	}
	/**
	 * 根据类型查询默认编辑器
	 * Find a default editor for the given type.
	 * @param requiredType the type to find an editor for
	 * @return the corresponding editor, or {@code null} if none
	 */
	@Nullable
	private PropertyEditor findDefaultEditor(@Nullable Class<?> requiredType) {
		PropertyEditor editor = null;
		//如果类型不为 null
		if (requiredType != null) {
			//获取默认的
			// No custom editor -> check BeanWrapperImpl's default editors.
			editor = this.propertyEditorRegistry.getDefaultEditor(requiredType);
			//如果没找到属性编辑器且结果类型不是字符串
			if (editor == null && String.class != requiredType) {
				//尝试根据类名加 Editor 后缀拼接全类名加载新的编辑器
				// No BeanWrapper default editor -> check standard JavaBean editor.
				editor = BeanUtils.findEditorByConvention(requiredType);
			}
		}
		//返回
		return editor;
	}

	/**
	 * 用 PropertyEditor 将值转换成给定的类型, 这里要注意的是
	 * - PropertyEditor 主要将字符串转其他对象
	 * Convert the value to the required type (if necessary from a String),
	 * using the given property editor.
	 * @param oldValue the previous value, if available (may be {@code null})
	 * @param newValue the proposed new value
	 * @param requiredType the type we must convert to
	 * (or {@code null} if not known, for example in case of a collection element)
	 * @param editor the PropertyEditor to use
	 * @return the new value, possibly the result of type conversion
	 * @throws IllegalArgumentException if type conversion failed
	 */
	@Nullable
	private Object doConvertValue(@Nullable Object oldValue, @Nullable Object newValue,
			@Nullable Class<?> requiredType, @Nullable PropertyEditor editor) {

		Object convertedValue = newValue;

		//如果 editor 不为 null && 要转换的值非 String 类型
		if (editor != null && !(convertedValue instanceof String)) {
			// Not a String -> use PropertyEditor's setValue.
			// With standard PropertyEditors, this will return the very same object;
			// we just want to allow special PropertyEditors to override setValue
			// for type conversion from non-String values to the required type.
			//非 String 类型转换成其他类型, 用 PropertyEditors.setValue 来处理, 也就是说可以覆盖 setValue 这个方法
			try {
				//调用 setValue
				editor.setValue(convertedValue);
				//再获取
				Object newConvertedValue = editor.getValue();
				//如果设置的值和获取的值引用不相等, 那么就证明有转换
				if (newConvertedValue != convertedValue) {
					//暂存转换后的值
					convertedValue = newConvertedValue;
					// Reset PropertyEditor: It already did a proper conversion.
					// Don't use it again for a setAsText call.
					//属性编辑器已经做了一层适当的转换, 也就是不再需要这个转换器
					editor = null;
				}
			}
			catch (Exception ex) {
				if (logger.isDebugEnabled()) {
					logger.debug("PropertyEditor [" + editor.getClass().getName() + "] does not support setValue call", ex);
				}
				// Swallow and proceed.
			}
		}

		Object returnValue = convertedValue;

		//如果结果类型不为 null, 且结果类型是非数组, 而转换后的值 convertedValue 是字符串数组类型
		if (requiredType != null && !requiredType.isArray() && convertedValue instanceof String[]) {
			// Convert String array to a comma-separated String.
			// Only applies if no PropertyEditor converted the String array before.
			// The CSV String will be passed into a PropertyEditor's setAsText method, if any.
			if (logger.isTraceEnabled()) {
				logger.trace("Converting String array to comma-delimited String [" + convertedValue + "]");
			}
			//逗号拼接数组成一个字符串
			convertedValue = StringUtils.arrayToCommaDelimitedString((String[]) convertedValue);
		}

		//如果 convertedValue 是字符串类型
		if (convertedValue instanceof String) {
			//属性编辑器不为 null
			if (editor != null) {
				// Use PropertyEditor's setAsText in case of a String value.
				if (logger.isTraceEnabled()) {
					logger.trace("Converting String to [" + requiredType + "] using property editor [" + editor + "]");
				}
				//强转成字符串
				String newTextValue = (String) convertedValue;
				//调用编辑器将字符串转换成结果类型, 并返回
				return doConvertTextValue(oldValue, newTextValue, editor);
			}
			//如果结果类型是字符串, 则直接返回
			else if (String.class == requiredType) {
				returnValue = convertedValue;
			}
		}

		//返回转换后的值
		return returnValue;
	}

	/**
	 * 调用 PropertyEditor.setAsText 进行转换
	 * Convert the given text value using the given property editor.
	 * @param oldValue the previous value, if available (may be {@code null})
	 * @param newTextValue the proposed text value
	 * @param editor the PropertyEditor to use
	 * @return the converted value
	 */
	private Object doConvertTextValue(@Nullable Object oldValue, String newTextValue, PropertyEditor editor) {
		try {
			editor.setValue(oldValue);
		}
		catch (Exception ex) {
			if (logger.isDebugEnabled()) {
				logger.debug("PropertyEditor [" + editor.getClass().getName() + "] does not support setValue call", ex);
			}
			// Swallow and proceed.
		}
		editor.setAsText(newTextValue);
		return editor.getValue();
	}

	/**
	 * 将给定对象, 转换成数组类型
	 */
	private Object convertToTypedArray(Object input, @Nullable String propertyName, Class<?> componentType) {
		//如果给定的值是集合类型
		if (input instanceof Collection) {
			//强转
			// Convert Collection elements to array elements.
			Collection<?> coll = (Collection<?>) input;
			//创建对应的数组
			Object result = Array.newInstance(componentType, coll.size());
			int i = 0;
			//遍历集合的元素
			for (Iterator<?> it = coll.iterator(); it.hasNext(); i++) {
				//转换每个元素的值
				Object value = convertIfNecessary(
						buildIndexedPropertyName(propertyName, i), null, it.next(), componentType);
				//设置值到数组
				Array.set(result, i, value);
			}
			//返回
			return result;
		}
		//如果给定对象是数组类型的
		else if (input.getClass().isArray()) {
			//给定的对象的组件类型和要转换的组件类型是一样的且没有当前类型的自定义编辑器
			// Convert array elements, if necessary.
			if (componentType.equals(input.getClass().getComponentType()) &&
					!this.propertyEditorRegistry.hasCustomEditorForElement(componentType, propertyName)) {
				//返回给定对象
				return input;
			}
			//如果数组 ComponentType 的类型不一样
			int arrayLength = Array.getLength(input);
			//创建结果类型的数组
			Object result = Array.newInstance(componentType, arrayLength);
			//遍历
			for (int i = 0; i < arrayLength; i++) {
				//转换元素的值
				Object value = convertIfNecessary(
						buildIndexedPropertyName(propertyName, i), null, Array.get(input, i), componentType);
				//设置数组的值
				Array.set(result, i, value);
			}
			//返回
			return result;
		}
		//如果是单个值
		else {
			//创建数组
			// A plain value: convert it to an array with a single component.
			Object result = Array.newInstance(componentType, 1);
			//将对象转换数组元素的类型
			Object value = convertIfNecessary(
					buildIndexedPropertyName(propertyName, 0), null, input, componentType);
			//设置值
			Array.set(result, 0, value);
			//返回结果值
			return result;
		}
	}

	/**
	 * 主要是目的是将集合的元素进行类型转换
	 * - 如果返回类型不是集合则不处理
	 */
	@SuppressWarnings("unchecked")
	private Collection<?> convertToTypedCollection(Collection<?> original, @Nullable String propertyName,
			Class<?> requiredType, @Nullable TypeDescriptor typeDescriptor) {

		//如果值类型是集合, 但返回的类型不是集合类型, 则返回
		if (!Collection.class.isAssignableFrom(requiredType)) {
			return original;
		}

		//能往下走的证明 requiredType 是集合类型
		//判断 requiredType 是否是给定的一些集合类型
		boolean approximable = CollectionFactory.isApproximableCollectionType(requiredType);
		//如果是非给定的集合, 且不能反射创建, 则直接返回
		if (!approximable && !canCreateCopy(requiredType)) {
			if (logger.isDebugEnabled()) {
				logger.debug("Custom Collection type [" + original.getClass().getName() +
						"] does not allow for creating a copy - injecting original Collection as-is");
			}
			//直接返回
			return original;
		}

		//判断给定的值是否是返回类型, 也就是是否返回原来传入的值
		boolean originalAllowed = requiredType.isInstance(original);
		//获取集合的元素类型泛型
		TypeDescriptor elementType = (typeDescriptor != null ? typeDescriptor.getElementTypeDescriptor() : null);
		//如果元素泛型类型为 null 且值是返回的类型, 且没有自定义的属性编辑器
		if (elementType == null && originalAllowed &&
				!this.propertyEditorRegistry.hasCustomEditorForElement(null, propertyName)) {
			//返回
			return original;
		}

		//获取迭代器
		Iterator<?> it;
		try {
			it = original.iterator();
		}
		catch (Throwable ex) {
			if (logger.isDebugEnabled()) {
				logger.debug("Cannot access Collection of type [" + original.getClass().getName() +
						"] - injecting original Collection as-is: " + ex);
			}
			return original;
		}

		Collection<Object> convertedCopy;
		try {
			//如果是通用的集合类型, 则创建
			if (approximable) {
				convertedCopy = CollectionFactory.createApproximateCollection(original, original.size());
			}
			//如果是非通用的集合类型, 则用默认构造器创建
			else {
				convertedCopy = (Collection<Object>)
						ReflectionUtils.accessibleConstructor(requiredType).newInstance();
			}
		}
		catch (Throwable ex) {
			if (logger.isDebugEnabled()) {
				logger.debug("Cannot create copy of Collection type [" + original.getClass().getName() +
						"] - injecting original Collection as-is: " + ex);
			}
			//报异常, 转换不了, 也直接返回
			return original;
		}

		//遍历
		for (int i = 0; it.hasNext(); i++) {
			//获取元素
			Object element = it.next();
			//构建带索引的属性名
			String indexedPropertyName = buildIndexedPropertyName(propertyName, i);
			//执行转换操作
			Object convertedElement = convertIfNecessary(indexedPropertyName, null, element,
					(elementType != null ? elementType.getType() : null) , elementType);
			try {
				//添加到复制的集合中
				convertedCopy.add(convertedElement);
			}
			catch (Throwable ex) {
				if (logger.isDebugEnabled()) {
					logger.debug("Collection type [" + original.getClass().getName() +
							"] seems to be read-only - injecting original Collection as-is: " + ex);
				}
				return original;
			}
			//如果转换后的类型和原来类型不一样, 则记录
			originalAllowed = originalAllowed && (element == convertedElement);
		}
		//判断是返回原集合还是转换后的集合
		return (originalAllowed ? original : convertedCopy);
	}

	/**
	 * 主要是转换 Map 的 kv 类型
	 */
	@SuppressWarnings("unchecked")
	private Map<?, ?> convertToTypedMap(Map<?, ?> original, @Nullable String propertyName,
			Class<?> requiredType, @Nullable TypeDescriptor typeDescriptor) {

		//如果结果集类型不是 Map , 则不处理
		if (!Map.class.isAssignableFrom(requiredType)) {
			return original;
		}

		//判断结果类型是否是通用 Map 类型
		boolean approximable = CollectionFactory.isApproximableMapType(requiredType);
		//如果不是通用 Map 且不能创建, 则不处理
		if (!approximable && !canCreateCopy(requiredType)) {
			if (logger.isDebugEnabled()) {
				logger.debug("Custom Map type [" + original.getClass().getName() +
						"] does not allow for creating a copy - injecting original Map as-is");
			}
			return original;
		}

		//判断是否可以使用原始值
		boolean originalAllowed = requiredType.isInstance(original);
		//获取 key 的泛型类型
		TypeDescriptor keyType = (typeDescriptor != null ? typeDescriptor.getMapKeyTypeDescriptor() : null);
		//获取 val 的泛型类型
		TypeDescriptor valueType = (typeDescriptor != null ? typeDescriptor.getMapValueTypeDescriptor() : null);
		//如果 key 的泛型类型是 null 且 val 的泛型类型是 null 且 可以使用原始值 , 且没有自定义的属性编辑器
		if (keyType == null && valueType == null && originalAllowed &&
				!this.propertyEditorRegistry.hasCustomEditorForElement(null, propertyName)) {
			//不处理, 直接返回
			return original;
		}

		//获取迭代器
		Iterator<?> it;
		try {
			it = original.entrySet().iterator();
		}
		catch (Throwable ex) {
			if (logger.isDebugEnabled()) {
				logger.debug("Cannot access Map of type [" + original.getClass().getName() +
						"] - injecting original Map as-is: " + ex);
			}
			//报错也直接返回
			return original;
		}

		Map<Object, Object> convertedCopy;
		try {
			//通用的 Map
			if (approximable) {
				//创建
				convertedCopy = CollectionFactory.createApproximateMap(original, original.size());
			}
			else {
				//其他 Map , 用默认构造器创建
				convertedCopy = (Map<Object, Object>)
						ReflectionUtils.accessibleConstructor(requiredType).newInstance();
			}
		}
		catch (Throwable ex) {
			if (logger.isDebugEnabled()) {
				logger.debug("Cannot create copy of Map type [" + original.getClass().getName() +
						"] - injecting original Map as-is: " + ex);
			}
			//返回
			return original;
		}

		//遍历
		while (it.hasNext()) {
			//获取 Entry
			Map.Entry<?, ?> entry = (Map.Entry<?, ?>) it.next();
			//获取 key
			Object key = entry.getKey();
			//获取 val
			Object value = entry.getValue();
			//拼接带索引的 属性名
			String keyedPropertyName = buildKeyedPropertyName(propertyName, key);
			//转换 key
			Object convertedKey = convertIfNecessary(keyedPropertyName, null, key,
					(keyType != null ? keyType.getType() : null), keyType);
			//转换 value
			Object convertedValue = convertIfNecessary(keyedPropertyName, null, value,
					(valueType!= null ? valueType.getType() : null), valueType);
			try {
				//放入集合
				convertedCopy.put(convertedKey, convertedValue);
			}
			catch (Throwable ex) {
				if (logger.isDebugEnabled()) {
					logger.debug("Map type [" + original.getClass().getName() +
							"] seems to be read-only - injecting original Map as-is: " + ex);
				}
				return original;
			}
			//记录是否使用原始值
			originalAllowed = originalAllowed && (key == convertedKey) && (value == convertedValue);
		}
		//返回
		return (originalAllowed ? original : convertedCopy);
	}

	/**
	 * 属性名拼接上索引
	 */
	@Nullable
	private String buildIndexedPropertyName(@Nullable String propertyName, int index) {
		return (propertyName != null ?
				propertyName + PropertyAccessor.PROPERTY_KEY_PREFIX + index + PropertyAccessor.PROPERTY_KEY_SUFFIX :
				null);
	}

	@Nullable
	private String buildKeyedPropertyName(@Nullable String propertyName, Object key) {
		return (propertyName != null ?
				propertyName + PropertyAccessor.PROPERTY_KEY_PREFIX + key + PropertyAccessor.PROPERTY_KEY_SUFFIX :
				null);
	}

	/**
	 * 判断给定的类型是否可以创建和复制, 判断规则如下:
	 * - 非接口
	 * - 非抽象
	 * - 是 public
	 * - 有默认构造器
	 */
	private boolean canCreateCopy(Class<?> requiredType) {
		return (!requiredType.isInterface() && !Modifier.isAbstract(requiredType.getModifiers()) &&
				Modifier.isPublic(requiredType.getModifiers()) && ClassUtils.hasConstructor(requiredType));
	}

}
