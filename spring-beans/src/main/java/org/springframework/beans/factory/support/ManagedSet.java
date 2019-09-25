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

package org.springframework.beans.factory.support;

import java.util.LinkedHashSet;
import java.util.Set;

import org.springframework.beans.BeanMetadataElement;
import org.springframework.beans.Mergeable;
import org.springframework.lang.Nullable;

/**
 * 可管理的 Set 集合
 * Tag collection class used to hold managed Set values, which may
 * include runtime bean references (to be resolved into bean objects).
 *
 * @author Juergen Hoeller
 * @author Rob Harrop
 * @since 21.01.2004
 * @param <E> the element type
 */
@SuppressWarnings("serial")
public class ManagedSet<E> extends LinkedHashSet<E> implements Mergeable, BeanMetadataElement {

	@Nullable
	private Object source;

	/**
	 * 元素类型
	 */
	@Nullable
	private String elementTypeName;

	/**
	 * 是否可以合并
	 */
	private boolean mergeEnabled;


	public ManagedSet() {
	}

	public ManagedSet(int initialCapacity) {
		super(initialCapacity);
	}


	/**
	 * Set the configuration source {@code Object} for this metadata element.
	 * <p>The exact type of the object will depend on the configuration mechanism used.
	 */
	public void setSource(@Nullable Object source) {
		this.source = source;
	}

	@Override
	@Nullable
	public Object getSource() {
		return this.source;
	}

	/**
	 * Set the default element type name (class name) to be used for this set.
	 */
	public void setElementTypeName(@Nullable String elementTypeName) {
		this.elementTypeName = elementTypeName;
	}

	/**
	 * Return the default element type name (class name) to be used for this set.
	 */
	@Nullable
	public String getElementTypeName() {
		return this.elementTypeName;
	}

	/**
	 * Set whether merging should be enabled for this collection,
	 * in case of a 'parent' collection value being present.
	 */
	public void setMergeEnabled(boolean mergeEnabled) {
		this.mergeEnabled = mergeEnabled;
	}

	@Override
	public boolean isMergeEnabled() {
		return this.mergeEnabled;
	}

	@Override
	@SuppressWarnings("unchecked")
	public Set<E> merge(@Nullable Object parent) {
		//如果不能并合, 则报错
		if (!this.mergeEnabled) {
			throw new IllegalStateException("Not allowed to merge when the 'mergeEnabled' property is set to 'false'");
		}
		//如果 parent 为 null, 则返回当前对象
		if (parent == null) {
			return this;
		}
		//如果 parent 不是 set , 则报错
		if (!(parent instanceof Set)) {
			throw new IllegalArgumentException("Cannot merge with object of type [" + parent.getClass() + "]");
		}
		//创建 ManagedSet
		Set<E> merged = new ManagedSet<>();
		//添加要合并的对象
		merged.addAll((Set<E>) parent);
		//添加当前对象
		merged.addAll(this);
		//返回合并后的结果
		return merged;
	}

}
