/*
 * Copyright 2002-2018 the original author or authors.
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

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Stream;

import org.springframework.lang.Nullable;
import org.springframework.util.StringUtils;

/**
 * PropertyValues 接口的默认实现, 主要表示多个 PropertyValue
 * The default implementation of the {@link PropertyValues} interface.
 * Allows simple manipulation of properties, and provides constructors
 * to support deep copy and construction from a Map.
 *
 * @author Rod Johnson
 * @author Juergen Hoeller
 * @author Rob Harrop
 * @since 13 May 2001
 */
@SuppressWarnings("serial")
public class MutablePropertyValues implements PropertyValues, Serializable {

	/**
	 * 配置的 PropertyValue 列表
	 */
	private final List<PropertyValue> propertyValueList;

	/**
	 * 这里主要记录后置处理处理过的属性列表, 如果 @autowired @value等, 免得重复处理
	 */
	@Nullable
	private Set<String> processedProperties;

	/**
	 * 是否转换
	 */
	private volatile boolean converted = false;


	/**
	 * Creates a new empty MutablePropertyValues object.
	 * <p>Property values can be added with the {@code add} method.
	 * @see #add(String, Object)
	 */
	public MutablePropertyValues() {
		this.propertyValueList = new ArrayList<>(0);
	}

	/**
	 * 深复制构造器
	 * Deep copy constructor. Guarantees PropertyValue references
	 * are independent, although it can't deep copy objects currently
	 * referenced by individual PropertyValue objects.
	 * @param original the PropertyValues to copy
	 * @see #addPropertyValues(PropertyValues)
	 */
	public MutablePropertyValues(@Nullable PropertyValues original) {
		// We can optimize this because it's all new:
		// There is no replacement of existing property values.
		//如果参数不为 null
		if (original != null) {
			//获取 original 的 PropertyValue
			PropertyValue[] pvs = original.getPropertyValues();
			//初始化列表
			this.propertyValueList = new ArrayList<>(pvs.length);
			//遍历, 逐个添加
			for (PropertyValue pv : pvs) {
				this.propertyValueList.add(new PropertyValue(pv));
			}
		}
		//如果参数为空
		else {
			//创建一个空列表
			this.propertyValueList = new ArrayList<>(0);
		}
	}

	/**
	 * 从 Map 中创建
	 * Construct a new MutablePropertyValues object from a Map.
	 * @param original a Map with property values keyed by property name Strings
	 * @see #addPropertyValues(Map)
	 */
	public MutablePropertyValues(@Nullable Map<?, ?> original) {
		// We can optimize this because it's all new:
		// There is no replacement of existing property values.
		//参数不为 null
		if (original != null) {
			//根据长度创建列表
			this.propertyValueList = new ArrayList<>(original.size());
			//逐个添加
			original.forEach((attrName, attrValue) -> this.propertyValueList.add(
					new PropertyValue(attrName.toString(), attrValue)));
		}
		else {
			//如果参数为 null, 则创建空列表
			this.propertyValueList = new ArrayList<>(0);
		}
	}

	/**
	 * 根据 PropertyValue 列表创建
	 * Construct a new MutablePropertyValues object using the given List of
	 * PropertyValue objects as-is.
	 * <p>This is a constructor for advanced usage scenarios.
	 * It is not intended for typical programmatic use.
	 * @param propertyValueList a List of PropertyValue objects
	 */
	public MutablePropertyValues(@Nullable List<PropertyValue> propertyValueList) {
		this.propertyValueList =
				(propertyValueList != null ? propertyValueList : new ArrayList<>());
	}


	/**
	 * Return the underlying List of PropertyValue objects in its raw form.
	 * The returned List can be modified directly, although this is not recommended.
	 * <p>This is an accessor for optimized access to all PropertyValue objects.
	 * It is not intended for typical programmatic use.
	 */
	public List<PropertyValue> getPropertyValueList() {
		return this.propertyValueList;
	}

	/**
	 * Return the number of PropertyValue entries in the list.
	 */
	public int size() {
		return this.propertyValueList.size();
	}

	/**
	 * 添加 PropertyValue
	 * Copy all given PropertyValues into this object. Guarantees PropertyValue
	 * references are independent, although it can't deep copy objects currently
	 * referenced by individual PropertyValue objects.
	 * @param other the PropertyValues to copy
	 * @return this in order to allow for adding multiple property values in a chain
	 */
	public MutablePropertyValues addPropertyValues(@Nullable PropertyValues other) {
		//如果 PropertyValues 不为 null
		if (other != null) {
			//获取 PropertyValue 数组
			PropertyValue[] pvs = other.getPropertyValues();
			//遍历
			for (PropertyValue pv : pvs) {
				//复制并添加
				addPropertyValue(new PropertyValue(pv));
			}
		}
		return this;
	}

	/**
	 * 从 Map 中添加 PropertyValue
	 * Add all property values from the given Map.
	 * @param other a Map with property values keyed by property name,
	 * which must be a String
	 * @return this in order to allow for adding multiple property values in a chain
	 */
	public MutablePropertyValues addPropertyValues(@Nullable Map<?, ?> other) {
		if (other != null) {
			other.forEach((attrName, attrValue) -> addPropertyValue(
					new PropertyValue(attrName.toString(), attrValue)));
		}
		return this;
	}

	/**
	 * 添加 PropertyValue , 并且如果存在相同名称且可以并合的则合并
	 * Add a PropertyValue object, replacing any existing one for the
	 * corresponding property or getting merged with it (if applicable).
	 * @param pv the PropertyValue object to add
	 * @return this in order to allow for adding multiple property values in a chain
	 */
	public MutablePropertyValues addPropertyValue(PropertyValue pv) {
		//遍历当前的 propertyValueList
		for (int i = 0; i < this.propertyValueList.size(); i++) {
			//获取当前 PropertyValue
			PropertyValue currentPv = this.propertyValueList.get(i);
			//如果 currentPv 与 pv 的名称相同
			if (currentPv.getName().equals(pv.getName())) {
				//如果需要, 做合并处理
				pv = mergeIfRequired(pv, currentPv);
				//设置 pv 到 propertyValueList 的指定位置
				setPropertyValueAt(pv, i);
				//直接返回
				return this;
			}
		}
		//如果没有相同名称的元素, 则直接添加到后面
		this.propertyValueList.add(pv);
		return this;
	}

	/**
	 * 添加 propertyName 和 propertyValue 两个
	 * Overloaded version of {@code addPropertyValue} that takes
	 * a property name and a property value.
	 * <p>Note: As of Spring 3.0, we recommend using the more concise
	 * and chaining-capable variant {@link #add}.
	 * @param propertyName name of the property
	 * @param propertyValue value of the property
	 * @see #addPropertyValue(PropertyValue)
	 */
	public void addPropertyValue(String propertyName, Object propertyValue) {
		addPropertyValue(new PropertyValue(propertyName, propertyValue));
	}

	/**
	 * Add a PropertyValue object, replacing any existing one for the
	 * corresponding property or getting merged with it (if applicable).
	 * @param propertyName name of the property
	 * @param propertyValue value of the property
	 * @return this in order to allow for adding multiple property values in a chain
	 */
	public MutablePropertyValues add(String propertyName, @Nullable Object propertyValue) {
		addPropertyValue(new PropertyValue(propertyName, propertyValue));
		return this;
	}

	/**
	 * 设置 pv 到指定位置
	 * Modify a PropertyValue object held in this object.
	 * Indexed from 0.
	 */
	public void setPropertyValueAt(PropertyValue pv, int i) {
		this.propertyValueList.set(i, pv);
	}

	/**
	 * 如果条件合适的话, 则合并
	 * Merges the value of the supplied 'new' {@link PropertyValue} with that of
	 * the current {@link PropertyValue} if merging is supported and enabled.
	 * @see Mergeable
	 */
	private PropertyValue mergeIfRequired(PropertyValue newPv, PropertyValue currentPv) {
		//获取新值
		Object value = newPv.getValue();
		//如果 value 是 Mergeable 类型
		if (value instanceof Mergeable) {
			//强转
			Mergeable mergeable = (Mergeable) value;
			//如果可以合并
			if (mergeable.isMergeEnabled()) {
				//合并
				Object merged = mergeable.merge(currentPv.getValue());
				//返回合并后的 PropertyValue
				return new PropertyValue(newPv.getName(), merged);
			}
		}
		//默认直接返回新的 PropertyValue
		return newPv;
	}

	/**
	 * Remove the given PropertyValue, if contained.
	 * @param pv the PropertyValue to remove
	 */
	public void removePropertyValue(PropertyValue pv) {
		this.propertyValueList.remove(pv);
	}

	/**
	 * Overloaded version of {@code removePropertyValue} that takes a property name.
	 * @param propertyName name of the property
	 * @see #removePropertyValue(PropertyValue)
	 */
	public void removePropertyValue(String propertyName) {
		this.propertyValueList.remove(getPropertyValue(propertyName));
	}


	@Override
	public Iterator<PropertyValue> iterator() {
		return Collections.unmodifiableList(this.propertyValueList).iterator();
	}

	@Override
	public Spliterator<PropertyValue> spliterator() {
		return Spliterators.spliterator(this.propertyValueList, 0);
	}

	@Override
	public Stream<PropertyValue> stream() {
		return this.propertyValueList.stream();
	}

	@Override
	public PropertyValue[] getPropertyValues() {
		return this.propertyValueList.toArray(new PropertyValue[0]);
	}

	@Override
	@Nullable
	public PropertyValue getPropertyValue(String propertyName) {
		//遍历
		for (PropertyValue pv : this.propertyValueList) {
			//根据指定名称
			if (pv.getName().equals(propertyName)) {
				//如果找到就直接返回
				return pv;
			}
		}
		return null;
	}

	/**
	 * Get the raw property value, if any.
	 * @param propertyName the name to search for
	 * @return the raw property value, or {@code null} if none found
	 * @since 4.0
	 * @see #getPropertyValue(String)
	 * @see PropertyValue#getValue()
	 */
	@Nullable
	public Object get(String propertyName) {
		PropertyValue pv = getPropertyValue(propertyName);
		return (pv != null ? pv.getValue() : null);
	}

	/**
	 * 从当前的 PropertyValues 中与 old 对比, 找出跟 old 不一样的元素组成 PropertyValues 返回
	 */
	@Override
	public PropertyValues changesSince(PropertyValues old) {
		//创建 MutablePropertyValues
		MutablePropertyValues changes = new MutablePropertyValues();
		//如果要添加的 PropertyValues 跟当前的引用相同
		if (old == this) {
			//直接返回 changes
			return changes;
		}

		//遍历当前 propertyValueList
		// for each property value in the new set
		for (PropertyValue newPv : this.propertyValueList) {
			//根据名称从 old 的 PropertyValues 中获取
			// if there wasn't an old one, add it
			PropertyValue pvOld = old.getPropertyValue(newPv.getName());
			//如果没找到或不相等
			if (pvOld == null || !pvOld.equals(newPv)) {
				//则添加到 changes
				changes.addPropertyValue(newPv);
			}
		}
		return changes;
	}

	@Override
	public boolean contains(String propertyName) {
		return (getPropertyValue(propertyName) != null ||
				(this.processedProperties != null && this.processedProperties.contains(propertyName)));
	}

	@Override
	public boolean isEmpty() {
		return this.propertyValueList.isEmpty();
	}


	/**
	 * Register the specified property as "processed" in the sense
	 * of some processor calling the corresponding setter method
	 * outside of the PropertyValue(s) mechanism.
	 * <p>This will lead to {@code true} being returned from
	 * a {@link #contains} call for the specified property.
	 * @param propertyName the name of the property.
	 */
	public void registerProcessedProperty(String propertyName) {
		if (this.processedProperties == null) {
			this.processedProperties = new HashSet<>(4);
		}
		this.processedProperties.add(propertyName);
	}

	/**
	 * Clear the "processed" registration of the given property, if any.
	 * @since 3.2.13
	 */
	public void clearProcessedProperty(String propertyName) {
		if (this.processedProperties != null) {
			this.processedProperties.remove(propertyName);
		}
	}

	/**
	 * Mark this holder as containing converted values only
	 * (i.e. no runtime resolution needed anymore).
	 */
	public void setConverted() {
		this.converted = true;
	}

	/**
	 * Return whether this holder contains converted values only ({@code true}),
	 * or whether the values still need to be converted ({@code false}).
	 */
	public boolean isConverted() {
		return this.converted;
	}


	@Override
	public boolean equals(@Nullable Object other) {
		return (this == other || (other instanceof MutablePropertyValues &&
				this.propertyValueList.equals(((MutablePropertyValues) other).propertyValueList)));
	}

	@Override
	public int hashCode() {
		return this.propertyValueList.hashCode();
	}

	@Override
	public String toString() {
		PropertyValue[] pvs = getPropertyValues();
		if (pvs.length > 0) {
			return "PropertyValues: length=" + pvs.length + "; " + StringUtils.arrayToDelimitedString(pvs, "; ");
		}
		return "PropertyValues: length=0";
	}

}
