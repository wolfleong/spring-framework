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

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import org.springframework.lang.Nullable;

/**
 * 方法复盖列表
 * Set of method overrides, determining which, if any, methods on a
 * managed object the Spring IoC container will override at runtime.
 *
 * <p>The currently supported {@link MethodOverride} variants are
 * {@link LookupOverride} and {@link ReplaceOverride}.
 *
 * @author Rod Johnson
 * @author Juergen Hoeller
 * @since 1.1
 * @see MethodOverride
 */
public class MethodOverrides {

	/**
	 * 记录着所有 MethodOverride
	 */
	private final Set<MethodOverride> overrides = Collections.synchronizedSet(new LinkedHashSet<>(2));

	/**
	 * 标记是否修改
	 * todo wolfleong 为什么要这个参数
	 */
	private volatile boolean modified = false;


	/**
	 * Create new MethodOverrides.
	 */
	public MethodOverrides() {
	}

	/**
	 * Deep copy constructor.
	 */
	public MethodOverrides(MethodOverrides other) {
		addOverrides(other);
	}


	/**
	 * 根据给定的 MethodOverrides , 复制到当前的 MethodOverrides 下
	 * Copy all given method overrides into this object.
	 */
	public void addOverrides(@Nullable MethodOverrides other) {
		//如果 MethodOverrides 不为 null
		if (other != null) {
			//设置 modified 为 true
			this.modified = true;
			//合并当前 other.overrides
			this.overrides.addAll(other.overrides);
		}
	}

	/**
	 * 添加一个 MethodOverride
	 * Add the given method override.
	 */
	public void addOverride(MethodOverride override) {
		this.modified = true;
		this.overrides.add(override);
	}

	/**
	 * 获取所有的 methodOverrides
	 * Return all method overrides contained by this object.
	 * @return a Set of MethodOverride objects
	 * @see MethodOverride
	 */
	public Set<MethodOverride> getOverrides() {
		this.modified = true;
		return this.overrides;
	}

	/**
	 * Return whether the set of method overrides is empty.
	 */
	public boolean isEmpty() {
		return (!this.modified || this.overrides.isEmpty());
	}

	/**
	 * Return the override for the given method, if any.
	 * @param method method to check for overrides for
	 * @return the method override, or {@code null} if none
	 */
	@Nullable
	public MethodOverride getOverride(Method method) {
		//如果没有修改过
		if (!this.modified) {
			//则返回 null
			return null;
		}
		synchronized (this.overrides) {
			MethodOverride match = null;
			//遍历所有的 MethodOverride
			for (MethodOverride candidate : this.overrides) {
				//如果方法匹配则赋值
				if (candidate.matches(method)) {
					//暂存
					match = candidate;
				}
			}
			//返回最后一个匹配 method 的 MethodOverride
			return match;
		}
	}


	@Override
	public boolean equals(@Nullable Object other) {
		if (this == other) {
			return true;
		}
		if (!(other instanceof MethodOverrides)) {
			return false;
		}
		MethodOverrides that = (MethodOverrides) other;
		return this.overrides.equals(that.overrides);

	}

	@Override
	public int hashCode() {
		return this.overrides.hashCode();
	}

}
