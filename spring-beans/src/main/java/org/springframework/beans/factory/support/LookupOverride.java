/*
 * Copyright 2002-2017 the original author or authors.
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

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import org.springframework.lang.Nullable;
import org.springframework.util.ObjectUtils;

/**
 * Represents an override of a method that looks up an object in the same IoC context.
 *
 * <p>Methods eligible for lookup override must not have arguments.
 *
 * @author Rod Johnson
 * @author Juergen Hoeller
 * @since 1.1
 */
public class LookupOverride extends MethodOverride {

	/**
	 * 要查找的 beanName
	 */
	@Nullable
	private final String beanName;

	/**
	 * 代理的方法
	 */
	@Nullable
	private Method method;


	/**
	 * 根据方法名和 beanName 创建
	 * Construct a new LookupOverride.
	 * @param methodName the name of the method to override
	 * @param beanName the name of the bean in the current {@code BeanFactory}
	 * that the overridden method should return (may be {@code null})
	 */
	public LookupOverride(String methodName, @Nullable String beanName) {
		super(methodName);
		this.beanName = beanName;
	}

	/**
	 * 根据方法名和 beanName 创建对象
	 * Construct a new LookupOverride.
	 * @param method the method to override
	 * @param beanName the name of the bean in the current {@code BeanFactory}
	 * that the overridden method should return (may be {@code null})
	 */
	public LookupOverride(Method method, @Nullable String beanName) {
		super(method.getName());
		this.method = method;
		this.beanName = beanName;
	}


	/**
	 * Return the name of the bean that should be returned by this method.
	 */
	@Nullable
	public String getBeanName() {
		return this.beanName;
	}

	/**
	 * Match the specified method by {@link Method} reference or method name.
	 * <p>For backwards compatibility reasons, in a scenario with overloaded
	 * non-abstract methods of the given name, only the no-arg variant of a
	 * method will be turned into a container-driven lookup method.
	 * <p>In case of a provided {@link Method}, only straight matches will
	 * be considered, usually demarcated by the {@code @Lookup} annotation.
	 */
	@Override
	public boolean matches(Method method) {
		//如果当前 LookupOverride 中的 method 不为 null
		if (this.method != null) {
			//则比较两个方法是否相等
			return method.equals(this.method);
		}
		//当前 LookupOverride 中的 method 为 null
		else {
			//方法名相同, 非重载方法或抽象方法或方法没有方法参数
			return (method.getName().equals(getMethodName()) && (!isOverloaded() ||
					Modifier.isAbstract(method.getModifiers()) || method.getParameterCount() == 0));
		}
	}


	@Override
	public boolean equals(@Nullable Object other) {
		if (!(other instanceof LookupOverride) || !super.equals(other)) {
			return false;
		}
		LookupOverride that = (LookupOverride) other;
		return (ObjectUtils.nullSafeEquals(this.method, that.method) &&
				ObjectUtils.nullSafeEquals(this.beanName, that.beanName));
	}

	@Override
	public int hashCode() {
		return (29 * super.hashCode() + ObjectUtils.nullSafeHashCode(this.beanName));
	}

	@Override
	public String toString() {
		return "LookupOverride for method '" + getMethodName() + "'";
	}

}
