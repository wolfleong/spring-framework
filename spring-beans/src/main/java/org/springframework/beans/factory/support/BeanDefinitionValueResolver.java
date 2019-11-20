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

package org.springframework.beans.factory.support;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import org.springframework.beans.BeanWrapper;
import org.springframework.beans.BeansException;
import org.springframework.beans.TypeConverter;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.BeanDefinitionStoreException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanDefinitionHolder;
import org.springframework.beans.factory.config.DependencyDescriptor;
import org.springframework.beans.factory.config.NamedBeanHolder;
import org.springframework.beans.factory.config.RuntimeBeanNameReference;
import org.springframework.beans.factory.config.RuntimeBeanReference;
import org.springframework.beans.factory.config.TypedStringValue;
import org.springframework.lang.Nullable;
import org.springframework.util.ClassUtils;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;

/**
 * Helper class for use in bean factory implementations,
 * resolving values contained in bean definition objects
 * into the actual values applied to the target bean instance.
 *
 * <p>Operates on an {@link AbstractBeanFactory} and a plain
 * {@link org.springframework.beans.factory.config.BeanDefinition} object.
 * Used by {@link AbstractAutowireCapableBeanFactory}.
 *
 * @author Juergen Hoeller
 * @since 1.2
 * @see AbstractAutowireCapableBeanFactory
 */
class BeanDefinitionValueResolver {

	private final AbstractAutowireCapableBeanFactory beanFactory;

	private final String beanName;

	private final BeanDefinition beanDefinition;

	private final TypeConverter typeConverter;


	/**
	 * Create a BeanDefinitionValueResolver for the given BeanFactory and BeanDefinition.
	 * @param beanFactory the BeanFactory to resolve against
	 * @param beanName the name of the bean that we work on
	 * @param beanDefinition the BeanDefinition of the bean that we work on
	 * @param typeConverter the TypeConverter to use for resolving TypedStringValues
	 */
	public BeanDefinitionValueResolver(AbstractAutowireCapableBeanFactory beanFactory, String beanName,
			BeanDefinition beanDefinition, TypeConverter typeConverter) {

		this.beanFactory = beanFactory;
		this.beanName = beanName;
		this.beanDefinition = beanDefinition;
		this.typeConverter = typeConverter;
	}


	/**
	 * 解析属性值, 对注入类型进行转换
	 * Given a PropertyValue, return a value, resolving any references to other
	 * beans in the factory if necessary. The value could be:
	 * <li>A BeanDefinition, which leads to the creation of a corresponding
	 * new bean instance. Singleton flags and names of such "inner beans"
	 * are always ignored: Inner beans are anonymous prototypes.
	 * <li>A RuntimeBeanReference, which must be resolved.
	 * <li>A ManagedList. This is a special collection that may contain
	 * RuntimeBeanReferences or Collections that will need to be resolved.
	 * <li>A ManagedSet. May also contain RuntimeBeanReferences or
	 * Collections that will need to be resolved.
	 * <li>A ManagedMap. In this case the value may be a RuntimeBeanReference
	 * or Collection that will need to be resolved.
	 * <li>An ordinary object or {@code null}, in which case it's left alone.
	 * @param argName the name of the argument that the value is defined for
	 * @param value the value object to resolve
	 * @return the resolved object
	 */
	@Nullable
	public Object resolveValueIfNecessary(Object argName, @Nullable Object value) {
		// We must check each value to see whether it requires a runtime reference
		// to another bean to be resolved.
		//如果值是引用类型
		if (value instanceof RuntimeBeanReference) {
			//强转成 RuntimeBeanReference
			RuntimeBeanReference ref = (RuntimeBeanReference) value;
			//调用解析引用类型
			return resolveReference(argName, ref);
		}
		//对属性值是引用容器中另一个Bean名称的解析
		else if (value instanceof RuntimeBeanNameReference) {
			//强转, 并且获取 beanName
			String refName = ((RuntimeBeanNameReference) value).getBeanName();
			//解析 beanName 引用字符串
			refName = String.valueOf(doEvaluate(refName));
			//如果容器没有这个 bean , 则报错
			if (!this.beanFactory.containsBean(refName)) {
				throw new BeanDefinitionStoreException(
						"Invalid bean name '" + refName + "' in bean reference for " + argName);
			}
			//直接返回 beanName
			return refName;
		}
		//对 bean 定义类型的解析, 主要是 bean 配置的内部 bean
		else if (value instanceof BeanDefinitionHolder) {
			// Resolve BeanDefinitionHolder: contains BeanDefinition with name and aliases.
			BeanDefinitionHolder bdHolder = (BeanDefinitionHolder) value;
			//解析 inner bean
			return resolveInnerBean(argName, bdHolder.getBeanName(), bdHolder.getBeanDefinition());
		}
		//todo wolfleong 暂时没见到过, 值类型是 BeanDefinition 的
		else if (value instanceof BeanDefinition) {
			// Resolve plain BeanDefinition, without contained name: use dummy name.
			BeanDefinition bd = (BeanDefinition) value;
			String innerBeanName = "(inner bean)" + BeanFactoryUtils.GENERATED_BEAN_NAME_SEPARATOR +
					ObjectUtils.getIdentityHexString(bd);
			return resolveInnerBean(argName, innerBeanName, bd);
		}
		//解析 依赖类型
		else if (value instanceof DependencyDescriptor) {
			Set<String> autowiredBeanNames = new LinkedHashSet<>(4);
			//解析依赖对象
			Object result = this.beanFactory.resolveDependency(
					(DependencyDescriptor) value, this.beanName, autowiredBeanNames, this.typeConverter);
			//遍历被依赖的 autowiredBeanNames
			for (String autowiredBeanName : autowiredBeanNames) {
				//如果容器中存在此自动注入的 autowiredBeanName, 注册依赖关系
				if (this.beanFactory.containsBean(autowiredBeanName)) {
					this.beanFactory.registerDependentBean(autowiredBeanName, this.beanName);
				}
			}
			//返回解析出来的对象
			return result;
		}
		//对集合数组类型的属性解析
		else if (value instanceof ManagedArray) {
			//强转
			// May need to resolve contained runtime references.
			ManagedArray array = (ManagedArray) value;
			//获取数组元素的类型
			Class<?> elementType = array.resolvedElementType;
			//如果类型为 null
			if (elementType == null) {
				//获取元素类型的名称
				String elementTypeName = array.getElementTypeName();
				//元素类型名称不为空的话
				if (StringUtils.hasText(elementTypeName)) {
					try {
						//加载类型
						elementType = ClassUtils.forName(elementTypeName, this.beanFactory.getBeanClassLoader());
						//设置数组的元素类型
						array.resolvedElementType = elementType;
					}
					catch (Throwable ex) {
						// Improve the message by showing the context.
						throw new BeanCreationException(
								this.beanDefinition.getResourceDescription(), this.beanName,
								"Error resolving array type for " + argName, ex);
					}
				}
				//如果数组元素类型不为空的话
				else {
					//elementType 默认给 Object
					elementType = Object.class;
				}
			}
			//解析数组元素
			return resolveManagedArray(argName, (List<?>) value, elementType);
		}
		////解析list类型的属性值
		else if (value instanceof ManagedList) {
			// May need to resolve contained runtime references.
			return resolveManagedList(argName, (List<?>) value);
		}
		//解析set类型的属性值
		else if (value instanceof ManagedSet) {
			// May need to resolve contained runtime references.
			return resolveManagedSet(argName, (Set<?>) value);
		}
		//解析map类型的属性值
		else if (value instanceof ManagedMap) {
			// May need to resolve contained runtime references.
			return resolveManagedMap(argName, (Map<?, ?>) value);
		}
		//解析props类型的属性值，props其实就是key和value均为字符串的map
		else if (value instanceof ManagedProperties) {
			//强转
			Properties original = (Properties) value;
			Properties copy = new Properties();
			//遍历所有的 prop
			original.forEach((propKey, propValue) -> {
				//如果 key 是字符串类型
				if (propKey instanceof TypedStringValue) {
					//解析字符串
					propKey = evaluate((TypedStringValue) propKey);
				}
				//如果 val 是字符串类型
				if (propValue instanceof TypedStringValue) {
					//解析字符串
					propValue = evaluate((TypedStringValue) propValue);
				}
				//如果 key 或 val 为 null, 则报异常
				if (propKey == null || propValue == null) {
					throw new BeanCreationException(
							this.beanDefinition.getResourceDescription(), this.beanName,
							"Error converting Properties key/value pair for " + argName + ": resolved to null");
				}
				//将解析后的 k, v 添加的 copy 中
				copy.put(propKey, propValue);
			});
			//返回
			return copy;
		}
		//解析 TypedStringValue 类型, 字符串字面量, 可能是其他类型
		else if (value instanceof TypedStringValue) {
			//强转
			// Convert value to target type here.
			TypedStringValue typedStringValue = (TypedStringValue) value;
			//解析字符串
			Object valueObject = evaluate(typedStringValue);
			try {
				//解析字符串的真正类型
				Class<?> resolvedTargetType = resolveTargetType(typedStringValue);
				//如果类型不为 null
				if (resolvedTargetType != null) {
					//转换并且返回
					return this.typeConverter.convertIfNecessary(valueObject, resolvedTargetType);
				}
				//如果字符串的真正类型为 null
				else {
					//直接返回字符串
					return valueObject;
				}
			}
			catch (Throwable ex) {
				// Improve the message by showing the context.
				throw new BeanCreationException(
						this.beanDefinition.getResourceDescription(), this.beanName,
						"Error converting typed String value for " + argName, ex);
			}
		}
		//如果 value 是 NullBean , 则直接返回 null
		else if (value instanceof NullBean) {
			return null;
		}
		//默认解析
		else {
			return evaluate(value);
		}
	}

	/**
	 * Evaluate the given value as an expression, if necessary.
	 * @param value the candidate value (may be an expression)
	 * @return the resolved value
	 */
	@Nullable
	protected Object evaluate(TypedStringValue value) {
		Object result = doEvaluate(value.getValue());
		if (!ObjectUtils.nullSafeEquals(result, value.getValue())) {
			value.setDynamic();
		}
		return result;
	}

	/**
	 * 如果可以, 将给定的值作为表达式求值
	 * Evaluate the given value as an expression, if necessary.
	 * @param value the original value (may be an expression)
	 * @return the resolved value if necessary, or the original value
	 */
	@Nullable
	protected Object evaluate(@Nullable Object value) {
		//如果 value 是字符串
		if (value instanceof String) {
			//强转字符串并解析
			return doEvaluate((String) value);
		}
		//如果对象是字符串数组
		else if (value instanceof String[]) {
			//强转
			String[] values = (String[]) value;
			//记录是否有解析成功
			boolean actuallyResolved = false;
			//记录解析后的结果
			Object[] resolvedValues = new Object[values.length];
			//遍历
			for (int i = 0; i < values.length; i++) {
				//原始字符串值
				String originalValue = values[i];
				//解析后的字符串值
				Object resolvedValue = doEvaluate(originalValue);
				//如果解析后的跟原始的不一样
				if (resolvedValue != originalValue) {
					//记录
					actuallyResolved = true;
				}
				resolvedValues[i] = resolvedValue;
			}
			//如果有解析成功, 则返回解析后的数组, 否则返回原来的数组
			return (actuallyResolved ? resolvedValues : values);
		}
		else {
			//其他对象则直接返回
			return value;
		}
	}

	/**
	 * Evaluate the given String value as an expression, if necessary.
	 * @param value the original value (may be an expression)
	 * @return the resolved value if necessary, or the original String value
	 */
	@Nullable
	private Object doEvaluate(@Nullable String value) {
		return this.beanFactory.evaluateBeanDefinitionString(value, this.beanDefinition);
	}

	/**
	 * Resolve the target type in the given TypedStringValue.
	 * @param value the TypedStringValue to resolve
	 * @return the resolved target type (or {@code null} if none specified)
	 * @throws ClassNotFoundException if the specified type cannot be resolved
	 * @see TypedStringValue#resolveTargetType
	 */
	@Nullable
	protected Class<?> resolveTargetType(TypedStringValue value) throws ClassNotFoundException {
		if (value.hasTargetType()) {
			return value.getTargetType();
		}
		return value.resolveTargetType(this.beanFactory.getBeanClassLoader());
	}

	/**
	 * Resolve a reference to another bean in the factory.
	 */
	@Nullable
	private Object resolveReference(Object argName, RuntimeBeanReference ref) {
		try {
			Object bean;
			//获取引用的 beanType
			Class<?> beanType = ref.getBeanType();
			//引用是否在父容器上
			if (ref.isToParent()) {
				//获取父容器
				BeanFactory parent = this.beanFactory.getParentBeanFactory();
				//如果父容器为 null , 则报错
				if (parent == null) {
					throw new BeanCreationException(
							this.beanDefinition.getResourceDescription(), this.beanName,
							"Cannot resolve reference to bean " + ref +
									" in parent factory: no parent factory available");
				}
				//如果 beanType 不为 null
				if (beanType != null) {
					//从父容器中根据类型获取 bean 实例
					bean = parent.getBean(beanType);
				}
				else {
					//解析 beanName 字符串
					//并解根据 beanName 获取 bean 实例
					bean = parent.getBean(String.valueOf(doEvaluate(ref.getBeanName())));
				}
			}
			//如果不在父容器上
			else {
				String resolvedName;
				//如果 beanType 不为 null
				if (beanType != null) {
					//根据类型在容器中找出 beanName 和 对应的实例
					NamedBeanHolder<?> namedBean = this.beanFactory.resolveNamedBean(beanType);
					//获取 bean 实例
					bean = namedBean.getBeanInstance();
					//获取 beanName
					resolvedName = namedBean.getBeanName();
				}
				//如果 beanType 是空
				else {
					//解析 beanName
					resolvedName = String.valueOf(doEvaluate(ref.getBeanName()));
					//获取 bean 的实例
					bean = this.beanFactory.getBean(resolvedName);
				}
				//注册依赖关系
				this.beanFactory.registerDependentBean(resolvedName, this.beanName);
			}
			//如果 bean 实例为 NullBean
			if (bean instanceof NullBean) {
				//重置为 null
				bean = null;
			}
			//返回  bean 实例
			return bean;
		}
		catch (BeansException ex) {
			throw new BeanCreationException(
					this.beanDefinition.getResourceDescription(), this.beanName,
					"Cannot resolve reference to bean '" + ref.getBeanName() + "' while setting " + argName, ex);
		}
	}

	/**
	 * 解析 inner bean 定义
	 * Resolve an inner bean definition.
	 * @param argName the name of the argument that the inner bean is defined for
	 * @param innerBeanName the name of the inner bean
	 * @param innerBd the bean definition for the inner bean
	 * @return the resolved inner bean instance
	 */
	@Nullable
	private Object resolveInnerBean(Object argName, String innerBeanName, BeanDefinition innerBd) {
		RootBeanDefinition mbd = null;
		try {
			//合并 innerBd 和 父 bean 定义
			mbd = this.beanFactory.getMergedBeanDefinition(innerBeanName, innerBd, this.beanDefinition);
			// Check given bean name whether it is unique. If not already unique,
			// add counter - increasing the counter until the name is unique.
			String actualInnerBeanName = innerBeanName;
			//如果 mdb 是单例的
			if (mbd.isSingleton()) {
				//处理 beanName , 使 beanName 变成唯一
				actualInnerBeanName = adaptInnerBeanName(innerBeanName);
			}
			//注册两个 beanName 之前的包含关系
			this.beanFactory.registerContainedBean(actualInnerBeanName, this.beanName);
			// Guarantee initialization of beans that the inner bean depends on.
			//获取配置的依赖
			String[] dependsOn = mbd.getDependsOn();
			//如果依赖不为 null
			if (dependsOn != null) {
				//遍历依赖
				for (String dependsOnBean : dependsOn) {
					//注册依赖关系
					this.beanFactory.registerDependentBean(dependsOnBean, actualInnerBeanName);
					//对被依赖的 bean 进行实例化
					this.beanFactory.getBean(dependsOnBean);
				}
			}
			//创建当前 inner bean 实例
			// Actually create the inner bean instance now...
			Object innerBean = this.beanFactory.createBean(actualInnerBeanName, mbd, null);
			//如果 innnerBean 是 FactoryBean
			if (innerBean instanceof FactoryBean) {
				//判断是否合成
				boolean synthetic = mbd.isSynthetic();
				//从 FactoryBean 中获取对象
				innerBean = this.beanFactory.getObjectFromFactoryBean(
						(FactoryBean<?>) innerBean, actualInnerBeanName, !synthetic);
			}
			//如果 innnerBean 是 NullBean
			if (innerBean instanceof NullBean) {
				//重置 innerBean 为 null
				innerBean = null;
			}
			//返回
			return innerBean;
		}
		catch (BeansException ex) {
			throw new BeanCreationException(
					this.beanDefinition.getResourceDescription(), this.beanName,
					"Cannot create inner bean '" + innerBeanName + "' " +
					(mbd != null && mbd.getBeanClassName() != null ? "of type [" + mbd.getBeanClassName() + "] " : "") +
					"while setting " + argName, ex);
		}
	}

	/**
	 * 处理理 beanName , 使 beanName 唯一
	 * Checks the given bean name whether it is unique. If not already unique,
	 * a counter is added, increasing the counter until the name is unique.
	 * @param innerBeanName the original name for the inner bean
	 * @return the adapted name for the inner bean
	 */
	private String adaptInnerBeanName(String innerBeanName) {
		//记录实际的 beanName
		String actualInnerBeanName = innerBeanName;
		//计数器
		int counter = 0;
		//迭代判断, 如果容器中已经使用这个 beanName
		while (this.beanFactory.isBeanNameInUse(actualInnerBeanName)) {
			//计数器加 1
			counter++;
			//拼接计数器
			actualInnerBeanName = innerBeanName + BeanFactoryUtils.GENERATED_BEAN_NAME_SEPARATOR + counter;
		}
		//返回唯一的 beanName
		return actualInnerBeanName;
	}

	/**
	 * 遍历数组的每个元素来解析
	 * For each element in the managed array, resolve reference if necessary.
	 */
	private Object resolveManagedArray(Object argName, List<?> ml, Class<?> elementType) {
		//创建数组对象
		Object resolved = Array.newInstance(elementType, ml.size());
		//遍历 ml
		for (int i = 0; i < ml.size(); i++) {
			//参数名加上下标作为单个元素的 argName
			//逐个元素解析
			Array.set(resolved, i, resolveValueIfNecessary(new KeyedArgName(argName, i), ml.get(i)));
		}
		//返回解析后的数组对象
		return resolved;
	}

	/**
	 * 遍历逐个解析
	 * For each element in the managed list, resolve reference if necessary.
	 */
	private List<?> resolveManagedList(Object argName, List<?> ml) {
		List<Object> resolved = new ArrayList<>(ml.size());
		for (int i = 0; i < ml.size(); i++) {
			resolved.add(resolveValueIfNecessary(new KeyedArgName(argName, i), ml.get(i)));
		}
		return resolved;
	}

	/**
	 * For each element in the managed set, resolve reference if necessary.
	 */
	private Set<?> resolveManagedSet(Object argName, Set<?> ms) {
		Set<Object> resolved = new LinkedHashSet<>(ms.size());
		int i = 0;
		for (Object m : ms) {
			resolved.add(resolveValueIfNecessary(new KeyedArgName(argName, i), m));
			i++;
		}
		return resolved;
	}

	/**
	 * 遍历逐个解析
	 * For each element in the managed map, resolve reference if necessary.
	 */
	private Map<?, ?> resolveManagedMap(Object argName, Map<?, ?> mm) {
		//记录解析的结果
		Map<Object, Object> resolved = new LinkedHashMap<>(mm.size());
		//遍历 map
		mm.forEach((key, value) -> {
			//解析 key
			Object resolvedKey = resolveValueIfNecessary(argName, key);
			//解析 value
			Object resolvedValue = resolveValueIfNecessary(new KeyedArgName(argName, key), value);
			resolved.put(resolvedKey, resolvedValue);
		});
		return resolved;
	}


	/**
	 * Holder class used for delayed toString building.
	 */
	private static class KeyedArgName {

		private final Object argName;

		private final Object key;

		public KeyedArgName(Object argName, Object key) {
			this.argName = argName;
			this.key = key;
		}

		@Override
		public String toString() {
			return this.argName + " with key " + BeanWrapper.PROPERTY_KEY_PREFIX +
					this.key + BeanWrapper.PROPERTY_KEY_SUFFIX;
		}
	}

}
