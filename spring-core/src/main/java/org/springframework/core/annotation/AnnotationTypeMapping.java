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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;

import org.springframework.core.annotation.AnnotationTypeMapping.MirrorSets.MirrorSet;
import org.springframework.lang.Nullable;
import org.springframework.util.ObjectUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StringUtils;

/**
 * 这个主要处理注解互为别名, 注解相同属性值等映射关系
 *
 * 假设注解的关系: TestRoot -> TestA -> TestB -> TestC ,
 * 在 @TestRoot 上面有 @TestA, @TestA 上面有 @TestB , @TestB 上有 @TestC
 *
 * Provides mapping information for a single annotation (or meta-annotation) in
 * the context of a root annotation type.
 *
 * @author Phillip Webb
 * @author Sam Brannen
 * @since 5.2
 * @see AnnotationTypeMappings
 */
final class AnnotationTypeMapping {
	//设 AnnotationTypeMapping 的某个实例为 M, M 所映射的注解为 A(@TestA)
	// A 中有 5 个属性方法: H0, H1, H2, H3, H4 .


	private static final MirrorSet[] EMPTY_MIRROR_SETS = new MirrorSet[0];


	/**
	 * 指向 A 所注释的注解的 AnnotationTypeMapping
	 *
	 * 指向前一个 AnnotationTypeMapping, 如:
	 *  - 当前 AnnotationTypeMapping 为 @TestB, 则 source 为 @TestA 的 AnnotationTypeMapping
	 *  - 当前 AnnotationTypeMapping 为 @TestRoot, 则 source 为 null, root 为 this
	 */
	@Nullable
	private final AnnotationTypeMapping source;

	/**
	 * 指向 root 的 AnnotationTypeMapping, 如 @TestRoot
	 */
	private final AnnotationTypeMapping root;

	/**
	 * M 到 root 的距离
	 * 当前 AnnotationTypeMapping 到 root 的距离
	 */
	private final int distance;

	/**
	 * 当前注解的类型, A.annotationType() , 即 A 的类型
	 */
	private final Class<? extends Annotation> annotationType;

	/**
	 * 当前 AnnotationTypeMapping(M) 到 root 的 AnnotationTypeMapping 之前的所有注解类型, 闭区间包括M和root
	 */
	private final List<Class<? extends Annotation>> metaTypes;

	/**
	 * 当前 M 注解的实例, 如果有值, 类型跟 annotationType 是一致的
	 */
	@Nullable
	private final Annotation annotation;

	/**
	 * 注解的属性方法
	 */
	private final AttributeMethods attributes;

	/**
	 * A 的属性的镜像集（镜像只包含 A 自己的属性，但计算是向前统计的）。镜像关系的计算是向前（向 root 方向）递归的
	 */
	private final MirrorSets mirrorSets;

	/**
	 * //这个数组的下标指的是 A 的属性（方法）的行文素引
	 * //数组的值指的是，root 中的行文素引
	 *
	 * //所以它很极客的将，A 的属性同 root 中的别名属性做了映射。
	 */
	private final int[] aliasMappings;

	/**
	 * 假设，A 的方法 H2 和 H3 互为镜像，H2 的方法名为“i”，且 root 中有同名方法“i”，“在 root 中的行为索引为 4
	 * 则 conventionMappings = [-1, -1,4,4, -1 ]
	 */
	private final int[] conventionMappings;

	/**
	 * annotationValueMappings 的下标是 A 中的属性方法的行文索引(即 H0, H1, H2 ...中的 0,1,2 ...)
	 * 值是该方法在可找到的最低层级上的镜像方法的行文索引
	 */
	private final int[] annotationValueMappings;

	/**
	 * annotationValueSource 的下标是 A 中的属性方法的行文索引(即 H0, H1, H2 ...中的 0,1,2 ...)
	 * 值是该方法在可找到的最低层级上的镜像方法所处的 AnnotationTypeMapping 实例
	 */
	private final AnnotationTypeMapping[] annotationValueSource;

	/**
	 * A 属性方法的别名集, 不考虑递归, 只包括他的直接别名
	 * 注意: key 是别名属性方法, value 是 A 自己的方法
	 * 这么写的原因是 A 的多个方法可能同时别名到另一个注解的同一个方法上
	 */
	private final Map<Method, List<Method>> aliasedBy;

	private final Set<Method> claimedAliases = new HashSet<>();


	AnnotationTypeMapping(@Nullable AnnotationTypeMapping source,
			Class<? extends Annotation> annotationType, @Nullable Annotation annotation) {

		this.source = source;
		//如果没有 source 为 null, 则 root 为 this
		this.root = (source != null ? source.getRoot() : this);
		//distance 的距离等于 source 离 root 的距离加 1
		this.distance = (source == null ? 0 : source.getDistance() + 1);
		//注解类型
		this.annotationType = annotationType;
		//如果 source 不为 null, 获取 source 与 root 之前的注解类型, 并且合并当前注解类型
		this.metaTypes = merge(
				source != null ? source.getMetaTypes() : null,
				annotationType);
		this.annotation = annotation;
		//获取注解的所有属性方法
		this.attributes = AttributeMethods.forAnnotationType(annotationType);
		//创建 MirrorSets
		this.mirrorSets = new MirrorSets();
		//填充默认值 -1
		this.aliasMappings = filledIntArray(this.attributes.size());
		//填充默认值 -1
		this.conventionMappings = filledIntArray(this.attributes.size());
		//填充默认值 -1
		this.annotationValueMappings = filledIntArray(this.attributes.size());
		//创建 AnnotationTypeMapping 数组
		this.annotationValueSource = new AnnotationTypeMapping[this.attributes.size()];
		//解析 aliasedBy
		this.aliasedBy = resolveAliasedForTargets();
		//处理别名
		processAliases();
		addConventionMappings();
		addConventionAnnotationValues();
	}


	/**
	 * 合并注解类型
	 */
	private static <T> List<T> merge(@Nullable List<T> existing, T element) {
		//如果 existing 为 null , 则只有 element
		if (existing == null) {
			return Collections.singletonList(element);
		}
		//创建列表并合并
		List<T> merged = new ArrayList<>(existing.size() + 1);
		merged.addAll(existing);
		merged.add(element);
		return Collections.unmodifiableList(merged);
	}

	private Map<Method, List<Method>> resolveAliasedForTargets() {
		Map<Method, List<Method>> aliasedBy = new HashMap<>();
		//遍历
		for (int i = 0; i < this.attributes.size(); i++) {
			//获取属性方法
			Method attribute = this.attributes.get(i);
			//扫描 @AliasFor 注解
			AliasFor aliasFor = AnnotationsScanner.getDeclaredAnnotation(attribute, AliasFor.class);
			//如果有 @AliasFor
			if (aliasFor != null) {
				//解析, 如果 attribute 与 target 在同一注解类类型中, 则必须互为别名
				Method target = resolveAliasTarget(attribute, aliasFor);
				//添加
				aliasedBy.computeIfAbsent(target, key -> new ArrayList<>()).add(attribute);
			}
		}
		//返回
		return Collections.unmodifiableMap(aliasedBy);
	}

	private Method resolveAliasTarget(Method attribute, AliasFor aliasFor) {
		return resolveAliasTarget(attribute, aliasFor, true);
	}

	private Method resolveAliasTarget(Method attribute, AliasFor aliasFor, boolean checkAliasPair) {
		// AliasFor 的 value 和 attribute 属性只能选其一
		if (StringUtils.hasText(aliasFor.value()) && StringUtils.hasText(aliasFor.attribute())) {
			throw new AnnotationConfigurationException(String.format(
					"In @AliasFor declared on %s, attribute 'attribute' and its alias 'value' " +
					"are present with values of '%s' and '%s', but only one is permitted.",
					AttributeMethods.describe(attribute), aliasFor.attribute(),
					aliasFor.value()));
		}
		//获取目标注解
		Class<? extends Annotation> targetAnnotation = aliasFor.annotation();
		//如果是 Annotation , 则表示当前注解
		if (targetAnnotation == Annotation.class) {
			targetAnnotation = this.annotationType;
		}
		//获取目标属性
		String targetAttributeName = aliasFor.attribute();
		if (!StringUtils.hasLength(targetAttributeName)) {
			targetAttributeName = aliasFor.value();
		}
		if (!StringUtils.hasLength(targetAttributeName)) {
			targetAttributeName = attribute.getName();
		}
		//获取目标属性方法
		Method target = AttributeMethods.forAnnotationType(targetAnnotation).get(targetAttributeName);

		//校验
		if (target == null) {
			if (targetAnnotation == this.annotationType) {
				throw new AnnotationConfigurationException(String.format(
						"@AliasFor declaration on %s declares an alias for '%s' which is not present.",
						AttributeMethods.describe(attribute), targetAttributeName));
			}
			throw new AnnotationConfigurationException(String.format(
					"%s is declared as an @AliasFor nonexistent %s.",
					StringUtils.capitalize(AttributeMethods.describe(attribute)),
					AttributeMethods.describe(targetAnnotation, targetAttributeName)));
		}
		if (target.equals(attribute)) {
			throw new AnnotationConfigurationException(String.format(
					"@AliasFor declaration on %s points to itself. " +
					"Specify 'annotation' to point to a same-named attribute on a meta-annotation.",
					AttributeMethods.describe(attribute)));
		}
		if (!isCompatibleReturnType(attribute.getReturnType(), target.getReturnType())) {
			throw new AnnotationConfigurationException(String.format(
					"Misconfigured aliases: %s and %s must declare the same return type.",
					AttributeMethods.describe(attribute),
					AttributeMethods.describe(target)));
		}

		//校验 checkAliasPair, 如果在同一个注解类型中, target 和 attribute 必须互为别名
		if (isAliasPair(target) && checkAliasPair) {
			//获取目标属性方法上的 AliasFor , 如果没有则报错
			AliasFor targetAliasFor = target.getAnnotation(AliasFor.class);
			if (targetAliasFor == null) {
				throw new AnnotationConfigurationException(String.format(
						"%s must be declared as an @AliasFor '%s'.",
						StringUtils.capitalize(AttributeMethods.describe(target)),
						attribute.getName()));
			}
			//获取目标的属性方法的 mirror
			Method mirror = resolveAliasTarget(target, targetAliasFor, false);
			//如果 mirror 不是当前属性方法, 则报错
			if (!mirror.equals(attribute)) {
				throw new AnnotationConfigurationException(String.format(
						"%s must be declared as an @AliasFor '%s', not '%s'.",
						StringUtils.capitalize(AttributeMethods.describe(target)),
						attribute.getName(), mirror.getName()));
			}
		}
		return target;
	}

	/**
	 * 检查是否在同一个注解类型中
	 */
	private boolean isAliasPair(Method target) {
		return target.getDeclaringClass().equals(this.annotationType);
	}

	private boolean isCompatibleReturnType(Class<?> attributeType, Class<?> targetType) {
		return Objects.equals(attributeType, targetType) ||
				Objects.equals(attributeType, targetType.getComponentType());
	}

	private void processAliases() {
		List<Method> aliases = new ArrayList<>();
		//遍历属性
		for (int i = 0; i < this.attributes.size(); i++) {
			aliases.clear();
			//添加当前属性方法
			aliases.add(this.attributes.get(i));
			//收集别名
			collectAliases(aliases);
			//当前属性有多个别名
			if (aliases.size() > 1) {
				processAliases(i, aliases);
			}
		}
	}

	/**
	 * 收集别名, 包括继承的注解
	 */
	private void collectAliases(List<Method> aliases) {
		AnnotationTypeMapping mapping = this;
		//从当前开始, 向前遍历 AnnotationTypeMapping, 直到 root 为止
		while (mapping != null) {
			int size = aliases.size();
			//遍历方法属性
			for (int j = 0; j < size; j++) {
				//获取当前的
				List<Method> additional = mapping.aliasedBy.get(aliases.get(j));
				if (additional != null) {
					aliases.addAll(additional);
				}
			}
			mapping = mapping.source;
		}
	}

	private void processAliases(int attributeIndex, List<Method> aliases) {
		//获取 root 别名的索引, 这个索引是 root 属性方法数组的
		int rootAttributeIndex = getFirstRootAttributeIndex(aliases);
		AnnotationTypeMapping mapping = this;
		//从当前 AnnotationTypeMapping , 往前遍历, 直到 root 的 AnnotationTypeMapping
		while (mapping != null) {
			//如果 root 的属性数组下标有效且当前 mapping 不是 root
			if (rootAttributeIndex != -1 && mapping != this.root) {
				//遍历当前 mapping 的属性列表
				for (int i = 0; i < mapping.attributes.size(); i++) {
					//aliases 的所有别名最终都是指向一个方法属性的
					//只要当前属性方法在别名列表中, 则表示 rootAttributeIndex 所指向的属性方法的别名为当前属性方法
					if (aliases.contains(mapping.attributes.get(i))) {
						mapping.aliasMappings[i] = rootAttributeIndex;
					}
				}
			}
			//更新当前 mapping 的镜像集
			mapping.mirrorSets.updateFrom(aliases);
			//记录 aliases
			mapping.claimedAliases.addAll(aliases);
			//当前注解实例不为 null
			if (mapping.annotation != null) {
				//解析出镜像的方法关联
				int[] resolvedMirrors = mapping.mirrorSets.resolve(null,
						mapping.annotation, ReflectionUtils::invokeMethod);
				//遍历当前 mapping 属性方法
				for (int i = 0; i < mapping.attributes.size(); i++) {
					//如果存在别名
					if (aliases.contains(mapping.attributes.get(i))) {
						//记录属性的值
						this.annotationValueMappings[attributeIndex] = resolvedMirrors[i];
						this.annotationValueSource[attributeIndex] = mapping;
					}
				}
			}
			mapping = mapping.source;
		}
	}

	/**
	 * 返回第一个 root 属性方法别名的 下标
	 */
	private int getFirstRootAttributeIndex(Collection<Method> aliases) {
		//获取所有属性方法
		AttributeMethods rootAttributes = this.root.getAttributes();
		//遍历
		for (int i = 0; i < rootAttributes.size(); i++) {
			//别名中包括 root 的属性方法, 则返回 root 的属性下标
			if (aliases.contains(rootAttributes.get(i))) {
				return i;
			}
		}
		return -1;
	}

	private void addConventionMappings() {
		if (this.distance == 0) {
			return;
		}
		//获取 root 的属性方法列表
		AttributeMethods rootAttributes = this.root.getAttributes();
		//当前的 conventionMappings
		int[] mappings = this.conventionMappings;
		//遍历
		for (int i = 0; i < mappings.length; i++) {
			//获取属性方法名
			String name = this.attributes.get(i).getName();
			//获取 MirrorSet
			MirrorSet mirrors = getMirrorSets().getAssigned(i);
			// root 获取同一样名称的 下标
			int mapped = rootAttributes.indexOf(name);
			//如果是非 value 属性方法, 且下标存在
			if (!MergedAnnotation.VALUE.equals(name) && mapped != -1) {
				//记录 root 同名方法下标
				mappings[i] = mapped;
				if (mirrors != null) {
					for (int j = 0; j < mirrors.size(); j++) {
						//将所有镜像属性方法的位置都改成 mapped
						mappings[mirrors.getAttributeIndex(j)] = mapped;
					}
				}
			}
		}
	}

	private void addConventionAnnotationValues() {
		//遍历当前属性方法
		for (int i = 0; i < this.attributes.size(); i++) {
			Method attribute = this.attributes.get(i);
			//判断是否 value 属性方法
			boolean isValueAttribute = MergedAnnotation.VALUE.equals(attribute.getName());
			AnnotationTypeMapping mapping = this;
			//如果 mapping 是非 root
			while (mapping != null && mapping.distance > 0) {
				//获取属性名的位置
				int mapped = mapping.getAttributes().indexOf(attribute.getName());
				//如果位置存在且
				if (mapped != -1  && isBetterConventionAnnotationValue(i, isValueAttribute, mapping)) {
					this.annotationValueMappings[i] = mapped;
					//记录 mapping
					this.annotationValueSource[i] = mapping;
				}
				mapping = mapping.source;
			}
		}
	}

	private boolean isBetterConventionAnnotationValue(int index, boolean isValueAttribute,
			AnnotationTypeMapping mapping) {
		//没初始化, 返回 true
		if (this.annotationValueMappings[index] == -1) {
			return true;
		}
		//获取存在属性名的距离
		int existingDistance = this.annotationValueSource[index].distance;
		//非 value 属性, distance 更短, 则返回 true
		return !isValueAttribute && existingDistance > mapping.distance;
	}

	/**
	 * Method called after all mappings have been set. At this point no further
	 * lookups from child mappings will occur.
	 */
	void afterAllMappingsSet() {
		validateAllAliasesClaimed();
		for (int i = 0; i < this.mirrorSets.size(); i++) {
			validateMirrorSet(this.mirrorSets.get(i));
		}
		this.claimedAliases.clear();
	}

	private void validateAllAliasesClaimed() {
		for (int i = 0; i < this.attributes.size(); i++) {
			Method attribute = this.attributes.get(i);
			AliasFor aliasFor = AnnotationsScanner.getDeclaredAnnotation(attribute, AliasFor.class);
			if (aliasFor != null && !this.claimedAliases.contains(attribute)) {
				Method target = resolveAliasTarget(attribute, aliasFor);
				throw new AnnotationConfigurationException(String.format(
						"@AliasFor declaration on %s declares an alias for %s which is not meta-present.",
						AttributeMethods.describe(attribute), AttributeMethods.describe(target)));
			}
		}
	}

	private void validateMirrorSet(MirrorSet mirrorSet) {
		Method firstAttribute = mirrorSet.get(0);
		Object firstDefaultValue = firstAttribute.getDefaultValue();
		for (int i = 1; i <= mirrorSet.size() - 1; i++) {
			Method mirrorAttribute = mirrorSet.get(i);
			Object mirrorDefaultValue = mirrorAttribute.getDefaultValue();
			if (firstDefaultValue == null || mirrorDefaultValue == null) {
				throw new AnnotationConfigurationException(String.format(
						"Misconfigured aliases: %s and %s must declare default values.",
						AttributeMethods.describe(firstAttribute), AttributeMethods.describe(mirrorAttribute)));
			}
			if (!ObjectUtils.nullSafeEquals(firstDefaultValue, mirrorDefaultValue)) {
				throw new AnnotationConfigurationException(String.format(
						"Misconfigured aliases: %s and %s must declare the same default value.",
						AttributeMethods.describe(firstAttribute), AttributeMethods.describe(mirrorAttribute)));
			}
		}
	}

	/**
	 * Get the root mapping.
	 * @return the root mapping
	 */
	AnnotationTypeMapping getRoot() {
		return this.root;
	}

	/**
	 * Get the source of the mapping or {@code null}.
	 * @return the source of the mapping
	 */
	@Nullable
	AnnotationTypeMapping getSource() {
		return this.source;
	}

	/**
	 * Get the distance of this mapping.
	 * @return the distance of the mapping
	 */
	int getDistance() {
		return this.distance;
	}

	/**
	 * Get the type of the mapped annotation.
	 * @return the annotation type
	 */
	Class<? extends Annotation> getAnnotationType() {
		return this.annotationType;
	}

	List<Class<? extends Annotation>> getMetaTypes() {
		return this.metaTypes;
	}

	/**
	 * Get the source annotation for this mapping. This will be the
	 * meta-annotation, or {@code null} if this is the root mapping.
	 * @return the source annotation of the mapping
	 */
	@Nullable
	Annotation getAnnotation() {
		return this.annotation;
	}

	/**
	 * Get the annotation attributes for the mapping annotation type.
	 * @return the attribute methods
	 */
	AttributeMethods getAttributes() {
		return this.attributes;
	}

	/**
	 * Get the related index of an alias mapped attribute, or {@code -1} if
	 * there is no mapping. The resulting value is the index of the attribute on
	 * the root annotation that can be invoked in order to obtain the actual
	 * value.
	 * @param attributeIndex the attribute index of the source attribute
	 * @return the mapped attribute index or {@code -1}
	 */
	int getAliasMapping(int attributeIndex) {
		return this.aliasMappings[attributeIndex];
	}

	/**
	 * Get the related index of a convention mapped attribute, or {@code -1}
	 * if there is no mapping. The resulting value is the index of the attribute
	 * on the root annotation that can be invoked in order to obtain the actual
	 * value.
	 * @param attributeIndex the attribute index of the source attribute
	 * @return the mapped attribute index or {@code -1}
	 */
	int getConventionMapping(int attributeIndex) {
		return this.conventionMappings[attributeIndex];
	}

	/**
	 * Get a mapped attribute value from the most suitable
	 * {@link #getAnnotation() meta-annotation}. The resulting value is obtained
	 * from the closest meta-annotation, taking into consideration both
	 * convention and alias based mapping rules. For root mappings, this method
	 * will always return {@code null}.
	 * @param attributeIndex the attribute index of the source attribute
	 * @return the mapped annotation value, or {@code null}
	 */
	@Nullable
	Object getMappedAnnotationValue(int attributeIndex) {
		int mapped = this.annotationValueMappings[attributeIndex];
		if (mapped == -1) {
			return null;
		}
		AnnotationTypeMapping source = this.annotationValueSource[attributeIndex];
		return ReflectionUtils.invokeMethod(source.attributes.get(mapped), source.annotation);
	}

	/**
	 * Determine if the specified value is equivalent to the default value of the
	 * attribute at the given index.
	 * @param attributeIndex the attribute index of the source attribute
	 * @param value the value to check
	 * @param valueExtractor the value extractor used to extract value from any
	 * nested annotations
	 * @return {@code true} if the value is equivalent to the default value
	 */
	boolean isEquivalentToDefaultValue(int attributeIndex, Object value,
			BiFunction<Method, Object, Object> valueExtractor) {

		Method attribute = this.attributes.get(attributeIndex);
		return isEquivalentToDefaultValue(attribute, value, valueExtractor);
	}

	/**
	 * Get the mirror sets for this type mapping.
	 * @return the mirrorSets the attribute mirror sets.
	 */
	MirrorSets getMirrorSets() {
		return this.mirrorSets;
	}


	private static int[] filledIntArray(int size) {
		int[] array = new int[size];
		Arrays.fill(array, -1);
		return array;
	}

	private static boolean isEquivalentToDefaultValue(Method attribute, Object value,
			BiFunction<Method, Object, Object> valueExtractor) {

		return areEquivalent(attribute.getDefaultValue(), value, valueExtractor);
	}

	private static boolean areEquivalent(@Nullable Object value, @Nullable Object extractedValue,
			BiFunction<Method, Object, Object> valueExtractor) {

		if (ObjectUtils.nullSafeEquals(value, extractedValue)) {
			return true;
		}
		if (value instanceof Class && extractedValue instanceof String) {
			return areEquivalent((Class<?>) value, (String) extractedValue);
		}
		if (value instanceof Class[] && extractedValue instanceof String[]) {
			return areEquivalent((Class[]) value, (String[]) extractedValue);
		}
		if (value instanceof Annotation) {
			return areEquivalent((Annotation) value, extractedValue, valueExtractor);
		}
		return false;
	}

	private static boolean areEquivalent(Class<?>[] value, String[] extractedValue) {
		if (value.length != extractedValue.length) {
			return false;
		}
		for (int i = 0; i < value.length; i++) {
			if (!areEquivalent(value[i], extractedValue[i])) {
				return false;
			}
		}
		return true;
	}

	private static boolean areEquivalent(Class<?> value, String extractedValue) {
		return value.getName().equals(extractedValue);
	}

	private static boolean areEquivalent(Annotation value, @Nullable Object extractedValue,
			BiFunction<Method, Object, Object> valueExtractor) {

		AttributeMethods attributes = AttributeMethods.forAnnotationType(value.annotationType());
		for (int i = 0; i < attributes.size(); i++) {
			Method attribute = attributes.get(i);
			if (!areEquivalent(ReflectionUtils.invokeMethod(attribute, value),
					valueExtractor.apply(attribute, extractedValue), valueExtractor)) {
				return false;
			}
		}
		return true;
	}


	/**
	 *
	 * 一个实例的容器，用来提供所有申明的镜子的细节
	 *
	 * - 镜像容器，两个属性互为镜像，不一定它们要 aliaseFor，比如它们分別 aliase 了父中的两个属性,
	 *   而这两个属性在父中是互为 aliasefor 的，则子中的两个属性也互为镜像, 所以，在这里，
	 *   spring 定义了一种比别名更泛化的概念，叫镜像，属性的镜像关系是根据属性关系的传导而得来的
	 *
	 * A collection of {@link MirrorSet} instances that provides details of all
	 * defined mirrors.
	 */
	class MirrorSets {

		/**
		 * assigned 的去重版
		 * [mirrorSet1, mirrorSet2]
		 */
		private MirrorSet[] mirrorSets;

		/**
		 * 假设一个注解有 6 个属性方法，a 是互为镜像的方法，a 和 b 是互为镜像的方法，e 和 f 是独立方法，
		 * 则 updateFrom(aliases(a)))或 updateFrom(aliases(b))，之后
		 * [mirrorSet1, mirrorSet1, null, null, null, null]
		 *
		 * updateFrom(aliases(a)))或 updateFrom(aliases(b)) ，之后
		 * [mirrorSet1, mirrorSet1, mirrorSet2, mirrorSet2, null, null]
		 */
		private final MirrorSet[] assigned;

		MirrorSets() {
			this.assigned = new MirrorSet[attributes.size()];
			this.mirrorSets = EMPTY_MIRROR_SETS;
		}

		void updateFrom(Collection<Method> aliases) {
			MirrorSet mirrorSet = null;
			int size = 0;
			int last = -1;
			//遍历当前属性方法
			for (int i = 0; i < attributes.size(); i++) {
				Method attribute = attributes.get(i);
				//如果当前属性方法在别名列表中
				if (aliases.contains(attribute)) {
					size++;
					//如果当前属性方法中有两个方法都是别名
					if (size > 1) {
						//创建 MirrorSet
						if (mirrorSet == null) {
							mirrorSet = new MirrorSet();
							this.assigned[last] = mirrorSet;
						}
						//两个继承下来的互为别名的位置, 用同一个 mirrorSet
						this.assigned[i] = mirrorSet;
					}
					//记录上一个别名的属性方法下标
					last = i;
				}
			}
			//如果有创建 mirrorSet
			if (mirrorSet != null) {
				//更新 mirrorSet 的 indexs
				mirrorSet.update();
				//创建 Set , 用于去重
				Set<MirrorSet> unique = new LinkedHashSet<>(Arrays.asList(this.assigned));
				//删除 null
				unique.remove(null);
				//变数组, 赋值
				this.mirrorSets = unique.toArray(EMPTY_MIRROR_SETS);
			}
		}

		int size() {
			return this.mirrorSets.length;
		}

		MirrorSet get(int index) {
			return this.mirrorSets[index];
		}

		@Nullable
		MirrorSet getAssigned(int attributeIndex) {
			return this.assigned[attributeIndex];
		}

		/**
		 * 得到一个数组，数组的下标指向 A 的属性，值指向有效的属性
		 *
		 * 什么是有效的属性呢？如果属性没有镜像，则它自己就是有效属性，
		 * 如果属性有镜像且镜像之一不是默认值，则这个不是默认值的镜像是有效属性
		 *
		 * 假设方法 H3 和 H4 是镜像属性，H4 具有非默认值，则 result 如下
		 * result = [0,1, 2,3,4] -> result = 40,1,2, 4, 4]
		 *
		 * @param source
		 * @param annotation
		 * @param valueExtractor
		 * @return
		 */
		int[] resolve(@Nullable Object source, @Nullable Object annotation,
				BiFunction<Method, Object, Object> valueExtractor) {

			//创建结果数组
			int[] result = new int[attributes.size()];
			//遍历初始化下标与值一样
			for (int i = 0; i < result.length; i++) {
				result[i] = i;
			}
			//遍历有 MirrorSet 个数
			for (int i = 0; i < size(); i++) {
				MirrorSet mirrorSet = get(i);
				//解析 MirrorSet
				int resolved = mirrorSet.resolve(source, annotation, valueExtractor);
				//遍历 mirrorSet 互为镜像方法个数
				for (int j = 0; j < mirrorSet.size; j++) {
					//属性方法下标 -> (有值的属性方法下标, 或者 -1)
					result[mirrorSet.indexes[j]] = resolved;
				}
			}
			return result;
		}


		/**
		 * MirrorSet 代表一组互为镜像的属性
		 * 假设，注解 A 的方法 H3 和方法 H4 互为镜像方法.
		 *  A 的某个实例记为 a，代表这两个镜像方法的 MirrorSet 实例为 m ,
		 *  则调用 m.resolve，可实例 a 中获得 H3 或 H4 其中不是默认值的那个方法的索引（即得到 3 或者 4)
		 *
		 * A single set of mirror attributes.
		 */
		class MirrorSet {

			/**
			 * 当前互为镜像的个数
			 */
			private int size;

			/**
			 * 当前互为镜像的属性方法的下标
			 * indexes下标 -> 属性方法索引下标
			 */
			private final int[] indexes = new int[attributes.size()];

			void update() {
				this.size = 0;
				//遍历填充 -1
				Arrays.fill(this.indexes, -1);
				//遍历 assigned
				for (int i = 0; i < MirrorSets.this.assigned.length; i++) {
					//如果互为镜像
					if (MirrorSets.this.assigned[i] == this) {
						//记录
						this.indexes[this.size] = i;
						this.size++;
					}
				}
			}

			/**
			 * 以 annotation 为执行器，找出它的非默认值的属性方法行文索引
			 * 这段逻辑意味着，一个注解中的相互镜像的属性方法:
			 *  - 如果有超过一个的属性设置了值，值不相同则会抛出异常
			 *  - 如果有超过一个的属性设置了值，值相同，则返回最后一个的属性方法行文索引
			 *  - 如果只有一个属性设置了值，则返回这个属性方法的行文索引
			 *  - 如果所有属性都是默认值，则 result=-1
			 *
			 * @param source
			 * @param annotation 这个是比较关键的，它会作为方法的执行器
			 * @param valueExtractor
			 * @param <A>
			 * @return
			 */
			<A> int resolve(@Nullable Object source, @Nullable A annotation,
					BiFunction<Method, Object, Object> valueExtractor) {

				int result = -1;
				Object lastValue = null;
				//遍历
				for (int i = 0; i < this.size; i++) {
					//获取属性方法
					Method attribute = attributes.get(this.indexes[i]);
					//获取值
					Object value = valueExtractor.apply(attribute, annotation);
					//是否默认值
					boolean isDefaultValue = (value == null ||
							isEquivalentToDefaultValue(attribute, value, valueExtractor));
					//是默认值, 则不处理
					//值相等也不处理
					if (isDefaultValue || ObjectUtils.nullSafeEquals(lastValue, value)) {
						continue;
					}
					//如果设置了不同的值, 则抛异常
					if (lastValue != null &&
							!ObjectUtils.nullSafeEquals(lastValue, value)) {
						String on = (source != null) ? " declared on " + source : "";
						throw new AnnotationConfigurationException(String.format(
								"Different @AliasFor mirror values for annotation [%s]%s; attribute '%s' " +
								"and its alias '%s' are declared with values of [%s] and [%s].",
								getAnnotationType().getName(), on,
								attributes.get(result).getName(),
								attribute.getName(),
								ObjectUtils.nullSafeToString(lastValue),
								ObjectUtils.nullSafeToString(value)));
					}
					//记录属性方法索引
					result = this.indexes[i];
					//记录最后的值
					lastValue = value;
				}
				return result;
			}

			int size() {
				return this.size;
			}

			Method get(int index) {
				int attributeIndex = this.indexes[index];
				return attributes.get(attributeIndex);
			}

			int getAttributeIndex(int index) {
				return this.indexes[index];
			}
		}
	}

}
