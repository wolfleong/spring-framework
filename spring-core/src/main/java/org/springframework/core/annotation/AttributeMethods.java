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

package org.springframework.core.annotation;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Map;

import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.ConcurrentReferenceHashMap;

/**
 * 注解上的属性方法列表
 * Provides a quick way to access the attribute methods of an {@link Annotation}
 * with consistent ordering as well as a few useful utility methods.
 *
 * @author Phillip Webb
 * @since 5.2
 */
final class AttributeMethods {

	/**
	 * 没有属性方法
	 */
	static final AttributeMethods NONE = new AttributeMethods(null, new Method[0]);


	/**
	 * 缓存
	 */
	private static final Map<Class<? extends Annotation>, AttributeMethods> cache =
			new ConcurrentReferenceHashMap<>();

	/**
	 * 属性方法比较器
	 */
	private static final Comparator<Method> methodComparator = (m1, m2) -> {
		if (m1 != null && m2 != null) {
			return m1.getName().compareTo(m2.getName());
		}
		return m1 != null ? -1 : 1;
	};


	/**
	 * 注解类
	 */
	@Nullable
	private final Class<? extends Annotation> annotationType;

	/**
	 * 属性方法列表
	 */
	private final Method[] attributeMethods;

	private final boolean[] canThrowTypeNotPresentException;

	/**
	 * 是否有默认值的属性方法
	 */
	private final boolean hasDefaultValueMethod;

	/**
	 * 是否有嵌套注解
	 */
	private final boolean hasNestedAnnotation;


	private AttributeMethods(@Nullable Class<? extends Annotation> annotationType, Method[] attributeMethods) {
		this.annotationType = annotationType;
		this.attributeMethods = attributeMethods;
		this.canThrowTypeNotPresentException = new boolean[attributeMethods.length];
		//是否有默认值的属性方法
		boolean foundDefaultValueMethod = false;
		//是否有嵌套注解
		boolean foundNestedAnnotation = false;
		//遍历属性方法列表
		for (int i = 0; i < attributeMethods.length; i++) {
			Method method = this.attributeMethods[i];
			//获取返回值类型
			Class<?> type = method.getReturnType();
			//记录是否有默认值
			if (method.getDefaultValue() != null) {
				foundDefaultValueMethod = true;
			}
			//返回值是注解或注解数组
			if (type.isAnnotation() ||
					(type.isArray() && type.getComponentType().isAnnotation())) {
				foundNestedAnnotation = true;
			}
			//设置可访问
			method.setAccessible(true);
			this.canThrowTypeNotPresentException[i] =
					type == Class.class ||
					type == Class[].class ||
					type.isEnum();
		}
		this.hasDefaultValueMethod = foundDefaultValueMethod;
		this.hasNestedAnnotation = foundNestedAnnotation;
	}


	/**
	 * 是否只有一个 value 方法属性
	 * Determine if this instance only contains a single attribute named
	 * {@code value}.
	 * @return {@code true} if there is only a value attribute
	 */
	boolean hasOnlyValueAttribute() {
		return (this.attributeMethods.length == 1 &&
				MergedAnnotation.VALUE.equals(this.attributeMethods[0].getName()));
	}


	/**
	 * Determine if values from the given annotation can be safely accessed without
	 * causing any {@link TypeNotPresentException TypeNotPresentExceptions}.
	 * @param annotation the annotation to check
	 * @return {@code true} if all values are present
	 * @see #validate(Annotation)
	 */
	boolean isValid(Annotation annotation) {
		assertAnnotation(annotation);
		for (int i = 0; i < size(); i++) {
			if (canThrowTypeNotPresentException(i)) {
				try {
					get(i).invoke(annotation);
				}
				catch (Throwable ex) {
					return false;
				}
			}
		}
		return true;
	}

	/**
	 * Check if values from the given annotation can be safely accessed without causing
	 * any {@link TypeNotPresentException TypeNotPresentExceptions}. In particular,
	 * this method is designed to cover Google App Engine's late arrival of such
	 * exceptions for {@code Class} values (instead of the more typical early
	 * {@code Class.getAnnotations() failure}.
	 * @param annotation the annotation to validate
	 * @throws IllegalStateException if a declared {@code Class} attribute could not be read
	 * @see #isValid(Annotation)
	 */
	void validate(Annotation annotation) {
		assertAnnotation(annotation);
		for (int i = 0; i < size(); i++) {
			if (canThrowTypeNotPresentException(i)) {
				try {
					get(i).invoke(annotation);
				}
				catch (Throwable ex) {
					throw new IllegalStateException("Could not obtain annotation attribute value for " +
							get(i).getName() + " declared on " + annotation.annotationType(), ex);
				}
			}
		}
	}

	private void assertAnnotation(Annotation annotation) {
		Assert.notNull(annotation, "Annotation must not be null");
		if (this.annotationType != null) {
			Assert.isInstanceOf(this.annotationType, annotation);
		}
	}

	/**
	 * Get the attribute with the specified name or {@code null} if no
	 * matching attribute exists.
	 * @param name the attribute name to find
	 * @return the attribute method or {@code null}
	 */
	@Nullable
	Method get(String name) {
		int index = indexOf(name);
		return index != -1 ? this.attributeMethods[index] : null;
	}

	/**
	 * Get the attribute at the specified index.
	 * @param index the index of the attribute to return
	 * @return the attribute method
	 * @throws IndexOutOfBoundsException if the index is out of range
	 * (<tt>index &lt; 0 || index &gt;= size()</tt>)
	 */
	Method get(int index) {
		return this.attributeMethods[index];
	}

	/**
	 * Determine if the attribute at the specified index could throw a
	 * {@link TypeNotPresentException} when accessed.
	 * @param index the index of the attribute to check
	 * @return {@code true} if the attribute can throw a
	 * {@link TypeNotPresentException}
	 */
	boolean canThrowTypeNotPresentException(int index) {
		return this.canThrowTypeNotPresentException[index];
	}

	/**
	 * Get the index of the attribute with the specified name, or {@code -1}
	 * if there is no attribute with the name.
	 * @param name the name to find
	 * @return the index of the attribute, or {@code -1}
	 */
	int indexOf(String name) {
		for (int i = 0; i < this.attributeMethods.length; i++) {
			if (this.attributeMethods[i].getName().equals(name)) {
				return i;
			}
		}
		return -1;
	}

	/**
	 * Get the index of the specified attribute, or {@code -1} if the
	 * attribute is not in this collection.
	 * @param attribute the attribute to find
	 * @return the index of the attribute, or {@code -1}
	 */
	int indexOf(Method attribute) {
		for (int i = 0; i < this.attributeMethods.length; i++) {
			if (this.attributeMethods[i].equals(attribute)) {
				return i;
			}
		}
		return -1;
	}

	/**
	 * Get the number of attributes in this collection.
	 * @return the number of attributes
	 */
	int size() {
		return this.attributeMethods.length;
	}

	/**
	 * Determine if at least one of the attribute methods has a default value.
	 * @return {@code true} if there is at least one attribute method with a default value
	 */
	boolean hasDefaultValueMethod() {
		return this.hasDefaultValueMethod;
	}

	/**
	 * Determine if at least one of the attribute methods is a nested annotation.
	 * @return {@code true} if there is at least one attribute method with a nested
	 * annotation type
	 */
	boolean hasNestedAnnotation() {
		return this.hasNestedAnnotation;
	}


	/**
	 * 获取注解类型的所有属性方法
	 * Get the attribute methods for the given annotation type.
	 * @param annotationType the annotation type
	 * @return the attribute methods for the annotation type
	 */
	static AttributeMethods forAnnotationType(@Nullable Class<? extends Annotation> annotationType) {
		//没有注解类型, 返回 NONE
		if (annotationType == null) {
			return NONE;
		}
		//缓存中获取, 没有则计算出来
		return cache.computeIfAbsent(annotationType, AttributeMethods::compute);
	}

	private static AttributeMethods compute(Class<? extends Annotation> annotationType) {
		//获取注解声明的方法
		Method[] methods = annotationType.getDeclaredMethods();
		int size = methods.length;
		//遍历
		for (int i = 0; i < methods.length; i++) {
			//如果非属性方法, 则置 null
			if (!isAttributeMethod(methods[i])) {
				methods[i] = null;
				size--;
			}
		}
		//如果没有属性方法, 则返回 null
		if (size == 0) {
			return NONE;
		}
		//排序, 空的排后面去
		Arrays.sort(methods, methodComparator);
		//复制属性方法数组
		Method[] attributeMethods = Arrays.copyOf(methods, size);
		//创建 AttributeMethods
		return new AttributeMethods(annotationType, attributeMethods);
	}

	/**
	 * 判断是否为属性方法
	 */
	private static boolean isAttributeMethod(Method method) {
		//没有参数且有返回值
		return (method.getParameterCount() == 0 && method.getReturnType() != void.class);
	}

	/**
	 * Create a description for the given attribute method suitable to use in
	 * exception messages and logs.
	 * @param attribute the attribute to describe
	 * @return a description of the attribute
	 */
	static String describe(@Nullable Method attribute) {
		if (attribute == null) {
			return "(none)";
		}
		return describe(attribute.getDeclaringClass(), attribute.getName());
	}

	/**
	 * Create a description for the given attribute method suitable to use in
	 * exception messages and logs.
	 * @param annotationType the annotation type
	 * @param attributeName the attribute name
	 * @return a description of the attribute
	 */
	static String describe(@Nullable Class<?> annotationType, @Nullable String attributeName) {
		if (attributeName == null) {
			return "(none)";
		}
		String in = (annotationType != null ? " in annotation [" + annotationType.getName() + "]" : "");
		return "attribute '" + attributeName + "'" + in;
	}

}
