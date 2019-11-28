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

package org.springframework.beans.factory.annotation;

import java.beans.PropertyDescriptor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.beans.MutablePropertyValues;
import org.springframework.beans.PropertyValues;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.lang.Nullable;
import org.springframework.util.ReflectionUtils;

/**
 * 用于管理注入元数据的内部类。不打算在应用程序中直接使用。
 * Internal class for managing injection metadata.
 * Not intended for direct use in applications.
 *
 * <p>Used by {@link AutowiredAnnotationBeanPostProcessor},
 * {@link org.springframework.context.annotation.CommonAnnotationBeanPostProcessor} and
 * {@link org.springframework.orm.jpa.support.PersistenceAnnotationBeanPostProcessor}.
 *
 * @author Juergen Hoeller
 * @since 2.5
 */
public class InjectionMetadata {

	/**
	 * 空的 InjectionMetadata 引用
	 * An empty {@code InjectionMetadata} instance with no-op callbacks.
	 * @since 5.2
	 */
	public static final InjectionMetadata EMPTY = new InjectionMetadata(Object.class, Collections.emptyList()) {
		@Override
		public void checkConfigMembers(RootBeanDefinition beanDefinition) {
		}
		@Override
		public void inject(Object target, @Nullable String beanName, @Nullable PropertyValues pvs) {
		}
		@Override
		public void clear(@Nullable PropertyValues pvs) {
		}
	};


	private static final Log logger = LogFactory.getLog(InjectionMetadata.class);

	/**
	 * 目标类
	 */
	private final Class<?> targetClass;

	/**
	 * 注入元素列表, 每个注入元素代表着一个 Field 或 设置值的方法
	 */
	private final Collection<InjectedElement> injectedElements;

	@Nullable
	private volatile Set<InjectedElement> checkedElements;


	/**
	 * Create a new {@code InjectionMetadata instance}.
	 * <p>Preferably use {@link #forElements} for reusing the {@link #EMPTY}
	 * instance in case of no elements.
	 * @param targetClass the target class
	 * @param elements the associated elements to inject
	 * @see #forElements
	 */
	public InjectionMetadata(Class<?> targetClass, Collection<InjectedElement> elements) {
		this.targetClass = targetClass;
		this.injectedElements = elements;
	}


	/**
	 * 检查配置的成员, 如 Field 或 Method
	 */
	public void checkConfigMembers(RootBeanDefinition beanDefinition) {
		//复制注入元素的列表
		Set<InjectedElement> checkedElements = new LinkedHashSet<>(this.injectedElements.size());
		//遍历
		for (InjectedElement element : this.injectedElements) {
			//获取成员
			Member member = element.getMember();
			//如果这个成员不是由外部配置管理的
			if (!beanDefinition.isExternallyManagedConfigMember(member)) {
				//将这个成员配置到外部管理列表中
				beanDefinition.registerExternallyManagedConfigMember(member);
				//将 此元素添加到 checkedElements 中
				checkedElements.add(element);
				if (logger.isTraceEnabled()) {
					logger.trace("Registered injected element on class [" + this.targetClass.getName() + "]: " + element);
				}
			}
		}
		//缓存起来
		this.checkedElements = checkedElements;
	}

	/**
	 * 执行注入逻辑
	 */
	public void inject(Object target, @Nullable String beanName, @Nullable PropertyValues pvs) throws Throwable {
		//获取 checkedElements
		Collection<InjectedElement> checkedElements = this.checkedElements;
		//真正执行注入的元素集合, 以 checkedElements 为优先,  如果 checkedElements 为 null , 则取 injectedElements,
		Collection<InjectedElement> elementsToIterate =
				(checkedElements != null ? checkedElements : this.injectedElements);
		//如果注入元素的集合不为空
		if (!elementsToIterate.isEmpty()) {
			//遍历
			for (InjectedElement element : elementsToIterate) {
				if (logger.isTraceEnabled()) {
					logger.trace("Processing injected element of bean '" + beanName + "': " + element);
				}
				//每个元素执行注入逻辑
				element.inject(target, beanName, pvs);
			}
		}
	}

	/**
	 * todo wolfleong 不懂这个方法的作用
	 * Clear property skipping for the contained elements.
	 * @since 3.2.13
	 */
	public void clear(@Nullable PropertyValues pvs) {
		Collection<InjectedElement> checkedElements = this.checkedElements;
		Collection<InjectedElement> elementsToIterate =
				(checkedElements != null ? checkedElements : this.injectedElements);
		//如果注入元素列表不为 null
		if (!elementsToIterate.isEmpty()) {
			//遍历注入的元素
			for (InjectedElement element : elementsToIterate) {
				//清除 skip
				element.clearPropertySkipping(pvs);
			}
		}
	}


	/**
	 * 根据 elements 集合列表创建 InjectionMetadata, 如果 elements 是空, 则创建空的 InjectionMetadata
	 * Return an {@code InjectionMetadata} instance, possibly for empty elements.
	 * @param elements the elements to inject (possibly empty)
	 * @param clazz the target class
	 * @return a new {@code InjectionMetadata} instance,
	 * or {@link #EMPTY} in case of no elements
	 * @since 5.2
	 */
	public static InjectionMetadata forElements(Collection<InjectedElement> elements, Class<?> clazz) {
		return (elements.isEmpty() ? InjectionMetadata.EMPTY : new InjectionMetadata(clazz, elements));
	}

	/**
	 * 判断是否需要刷新
	 * 如果 metadata 是 null, 或者目标类不一样, 则返回 true
	 * Check whether the given injection metadata needs to be refreshed.
	 * @param metadata the existing metadata instance
	 * @param clazz the current target class
	 * @return {@code true} indicating a refresh, {@code false} otherwise
	 */
	public static boolean needsRefresh(@Nullable InjectionMetadata metadata, Class<?> clazz) {
		return (metadata == null || metadata.targetClass != clazz);
	}


	/**
	 * 单个注入元素的抽象类
	 * A single injected element.
	 */
	public abstract static class InjectedElement {

		/**
		 * 成员反射字段
		 */
		protected final Member member;

		/**
		 * 是否为字段
		 */
		protected final boolean isField;

		/**
		 * 属性内省
		 */
		@Nullable
		protected final PropertyDescriptor pd;

		/**
		 * 是否跳过此注入元素
		 * todo wolfleong 不太懂这个标识的作用
		 */
		@Nullable
		protected volatile Boolean skip;

		protected InjectedElement(Member member, @Nullable PropertyDescriptor pd) {
			this.member = member;
			this.isField = (member instanceof Field);
			this.pd = pd;
		}

		public final Member getMember() {
			return this.member;
		}

		protected final Class<?> getResourceType() {
			//如果是字段
			if (this.isField) {
				//强转成 Field , 获取字段的类型
				return ((Field) this.member).getType();
			}
			//如果内省器不为 null, 也就是 setter
			else if (this.pd != null) {
				//获取属性的类型
				return this.pd.getPropertyType();
			}
			//如果是方法
			else {
				//获取方法参数的类型
				return ((Method) this.member).getParameterTypes()[0];
			}
		}

		protected final void checkResourceType(Class<?> resourceType) {
			//如果是字段
			if (this.isField) {
				//获取字段类型
				Class<?> fieldType = ((Field) this.member).getType();
				//如果给定的类型和字段类型 没有关系, 则报错
				if (!(resourceType.isAssignableFrom(fieldType) || fieldType.isAssignableFrom(resourceType))) {
					throw new IllegalStateException("Specified field type [" + fieldType +
							"] is incompatible with resource type [" + resourceType.getName() + "]");
				}
			}
			//如果不是字段
			else {
				//获取方法参数的类型
				Class<?> paramType =
						(this.pd != null ? this.pd.getPropertyType() : ((Method) this.member).getParameterTypes()[0]);
				//如果参数类型和给定类型不没有关系, 则报错
				if (!(resourceType.isAssignableFrom(paramType) || paramType.isAssignableFrom(resourceType))) {
					throw new IllegalStateException("Specified parameter type [" + paramType +
							"] is incompatible with resource type [" + resourceType.getName() + "]");
				}
			}
		}

		/**
		 * 这个方法没有执行, 先不看
		 * Either this or {@link #getResourceToInject} needs to be overridden.
		 */
		protected void inject(Object target, @Nullable String requestingBeanName, @Nullable PropertyValues pvs)
				throws Throwable {

			if (this.isField) {
				Field field = (Field) this.member;
				ReflectionUtils.makeAccessible(field);
				field.set(target, getResourceToInject(target, requestingBeanName));
			}
			else {
				if (checkPropertySkipping(pvs)) {
					return;
				}
				try {
					Method method = (Method) this.member;
					ReflectionUtils.makeAccessible(method);
					method.invoke(target, getResourceToInject(target, requestingBeanName));
				}
				catch (InvocationTargetException ex) {
					throw ex.getTargetException();
				}
			}
		}

		/**
		 * todo wolfleong 不太懂
		 * Check whether this injector's property needs to be skipped due to
		 * an explicit property value having been specified. Also marks the
		 * affected property as processed for other processors to ignore it.
		 */
		protected boolean checkPropertySkipping(@Nullable PropertyValues pvs) {
			//获取 skip
			Boolean skip = this.skip;
			//如果 skip 不为 null
			if (skip != null) {
				//直接返回 skip
				return skip;
			}
			//如果给定 pvs 为 null
			if (pvs == null) {
				//设置当前 skip 为 false
				this.skip = false;
				//返回 false
				return false;
			}
			//同步处理
			synchronized (pvs) {
				//获取 skip
				skip = this.skip;
				//如果 skip 不为 null
				if (skip != null) {
					//返回
					return skip;
				}
				//如果当削 pd 不为 null
				if (this.pd != null) {
					//判断 pvs 是否包括当前属性名, 如果包括则说明当前属性已经处理过了, 则设置 skip 为 true, 直接跳过
					if (pvs.contains(this.pd.getName())) {
						// Explicit value provided as part of the bean definition.
						this.skip = true;
						return true;
					}
					//如果没有包括当前属性, 且是记录属性的是 MutablePropertyValues
					else if (pvs instanceof MutablePropertyValues) {
						//将当前属性注册到已处理中
						((MutablePropertyValues) pvs).registerProcessedProperty(this.pd.getName());
					}
				}
				//默认返回 false
				this.skip = false;
				return false;
			}
		}

		/**
		 * Clear property skipping for this element.
		 * @since 3.2.13
		 */
		protected void clearPropertySkipping(@Nullable PropertyValues pvs) {
			//如果 bean 所有属性封装为 null
			if (pvs == null) {
				//不处理, 直接返回
				return;
			}
			//同步
			synchronized (pvs) {
				//如果 skip 为 false 且 pd != null 且 pvs 是 MutablePropertyValues 实例
				if (Boolean.FALSE.equals(this.skip) && this.pd != null && pvs instanceof MutablePropertyValues) {
					//删除已经处理的 属性名
					((MutablePropertyValues) pvs).clearProcessedProperty(this.pd.getName());
				}
			}
		}

		/**
		 * 需要子类覆盖
		 * Either this or {@link #inject} needs to be overridden.
		 */
		@Nullable
		protected Object getResourceToInject(Object target, @Nullable String requestingBeanName) {
			return null;
		}

		@Override
		public boolean equals(@Nullable Object other) {
			//如果引用相等, 则直接返回 true
			if (this == other) {
				return true;
			}
			//如果类型不对, 则返回 false
			if (!(other instanceof InjectedElement)) {
				return false;
			}
			//强转
			InjectedElement otherElement = (InjectedElement) other;
			//比较反射字段
			return this.member.equals(otherElement.member);
		}

		@Override
		public int hashCode() {
			return this.member.getClass().hashCode() * 29 + this.member.getName().hashCode();
		}

		@Override
		public String toString() {
			return getClass().getSimpleName() + " for " + this.member;
		}
	}

}
