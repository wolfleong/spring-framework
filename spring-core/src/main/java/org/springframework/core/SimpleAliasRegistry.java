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

package org.springframework.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import org.springframework.util.StringValueResolver;

/**
 * AliasRegistry 的简单实现
 * Simple implementation of the {@link AliasRegistry} interface.
 * Serves as base class for
 * {@link org.springframework.beans.factory.support.BeanDefinitionRegistry}
 * implementations.
 *
 * @author Juergen Hoeller
 * @since 2.5.2
 */
public class SimpleAliasRegistry implements AliasRegistry {

	//日志对象
	/** Logger available to subclasses. */
	protected final Log logger = LogFactory.getLog(getClass());

	//别名映射, alias -> name
	/** Map from alias to canonical name. */
	private final Map<String, String> aliasMap = new ConcurrentHashMap<>(16);


	@Override
	public void registerAlias(String name, String alias) {
		// name 不能为空
		Assert.hasText(name, "'name' must not be empty");
		// alias 不能为空
		Assert.hasText(alias, "'alias' must not be empty");
		//同步处理
		synchronized (this.aliasMap) {
			//如果名称和别名一样
			if (alias.equals(name)) {
				//直接删除这个别名
				this.aliasMap.remove(alias);
				if (logger.isDebugEnabled()) {
					logger.debug("Alias definition '" + alias + "' ignored since it points to same name");
				}
			}
			else {
				//获取别名对应的名称
				String registeredName = this.aliasMap.get(alias);
				//如果名称不为空
				if (registeredName != null) {
					//别名已经存在, 而且存在的跟要注册的一样, 则不做处理
					if (registeredName.equals(name)) {
						// An existing alias - no need to re-register
						return;
					}
					//如果不允许覆盖, 则报异常
					if (!allowAliasOverriding()) {
						throw new IllegalStateException("Cannot define alias '" + alias + "' for name '" +
								name + "': It is already registered for name '" + registeredName + "'.");
					}
					if (logger.isDebugEnabled()) {
						logger.debug("Overriding alias '" + alias + "' definition for registered name '" +
								registeredName + "' with new target name '" + name + "'");
					}
				}
				//检查有没有循环引用
				checkForAliasCircle(name, alias);
				//注册
				this.aliasMap.put(alias, name);
				if (logger.isTraceEnabled()) {
					logger.trace("Alias definition '" + alias + "' registered for name '" + name + "'");
				}
			}
		}
	}

	/**
	 * Return whether alias overriding is allowed.
	 * Default is {@code true}.
	 */
	protected boolean allowAliasOverriding() {
		//别名默认是可以覆盖的
		return true;
	}

	/**
	 * Determine whether the given name has the given alias registered.
	 * @param name the name to check
	 * @param alias the alias to look for
	 * @since 4.2.1
	 */
	public boolean hasAlias(String name, String alias) {
		//遍历已经注册的别名
		for (Map.Entry<String, String> entry : this.aliasMap.entrySet()) {
			//获取已经注册的名称
			String registeredName = entry.getValue();
			//如果找到一样的名称
			if (registeredName.equals(name)) {
				//获取已经注册的别名
				String registeredAlias = entry.getKey();
				//已经注册的别名和当前指定的别名一样, 或 已经注册的 registeredAlias 和 alias 存在, 则表明此别名存在
				if (registeredAlias.equals(alias) || hasAlias(registeredAlias, alias)) {
					return true;
				}
			}
		}
		//找不到, 则返回 flase
		return false;
	}

	@Override
	public void removeAlias(String alias) {
		//同步处理
		synchronized (this.aliasMap) {
			//获取别名对应的名称
			String name = this.aliasMap.remove(alias);
			//如果名称是 null , 则报异常
			if (name == null) {
				throw new IllegalStateException("No alias '" + alias + "' registered");
			}
		}
	}

	@Override
	public boolean isAlias(String name) {
		return this.aliasMap.containsKey(name);
	}

	@Override
	public String[] getAliases(String name) {
		//保存名称的别名列表
		List<String> result = new ArrayList<>();
		synchronized (this.aliasMap) {
			retrieveAliases(name, result);
		}
		//集合变数组
		return StringUtils.toStringArray(result);
	}

	/**
	 * Transitively retrieve all aliases for the given name.
	 * @param name the target name to find aliases for
	 * @param result the resulting aliases list
	 */
	private void retrieveAliases(String name, List<String> result) {
		//遍历
		this.aliasMap.forEach((alias, registeredName) -> {
			//如果找到对应的名称
			if (registeredName.equals(name)) {
				//将别名添加到列表
				result.add(alias);
				//以当前别名作为名称, 继续找
				retrieveAliases(alias, result);
			}
		});
	}

	/**
	 * Resolve all alias target names and aliases registered in this
	 * factory, applying the given StringValueResolver to them.
	 * <p>The value resolver may for example resolve placeholders
	 * in target bean names and even in alias names.
	 * @param valueResolver the StringValueResolver to apply
	 */
	public void resolveAliases(StringValueResolver valueResolver) {
		Assert.notNull(valueResolver, "StringValueResolver must not be null");
		//同步处理
		synchronized (this.aliasMap) {
			//拷贝别名
			Map<String, String> aliasCopy = new HashMap<>(this.aliasMap);
			//遍历
			aliasCopy.forEach((alias, registeredName) -> {
				//解析别名的占位符
				String resolvedAlias = valueResolver.resolveStringValue(alias);
				//解析注册的名称的占位符
				String resolvedName = valueResolver.resolveStringValue(registeredName);
				//如果有任意一个为 null 或别名和注册的名称一样, 则直接删除
				if (resolvedAlias == null || resolvedName == null || resolvedAlias.equals(resolvedName)) {
					this.aliasMap.remove(alias);
				}
				//如果别名和注册的名称不相等
				else if (!resolvedAlias.equals(alias)) {
					//获取解析后的别名的注册的名称
					String existingName = this.aliasMap.get(resolvedAlias);
					//如果原注册名
					if (existingName != null) {
						//如果注册名与解析后的注册名相等, 也就是解析后的别名已经注册, 删除原来就行
						if (existingName.equals(resolvedName)) {
							//删除原有占位符的别名
							// Pointing to existing alias - just remove placeholder
							this.aliasMap.remove(alias);
							//直接返回
							return;
						}
						throw new IllegalStateException(
								"Cannot register resolved alias '" + resolvedAlias + "' (original: '" + alias +
								"') for name '" + resolvedName + "': It is already registered for name '" +
								registeredName + "'.");
					}
					//如果 existingName 为空, 则表示解析后的别名还没存在
					//检测别名是否循环依赖
					checkForAliasCircle(resolvedName, resolvedAlias);
					//删除原有点占位符的别名
					this.aliasMap.remove(alias);
					//添加新的别名和注册名的关系
					this.aliasMap.put(resolvedAlias, resolvedName);
				}
				// alias 和 resolvedAlias 相等, 且解析后的 resolvedName 和 registeredName 不相等, 用解析后的注册名替换掉原来的就好了
				else if (!registeredName.equals(resolvedName)) {
					this.aliasMap.put(alias, resolvedName);
				}
			});
		}
	}

	/**
	 * Check whether the given name points back to the given alias as an alias
	 * in the other direction already, catching a circular reference upfront
	 * and throwing a corresponding IllegalStateException.
	 * @param name the candidate name
	 * @param alias the candidate alias
	 * @see #registerAlias
	 * @see #hasAlias
	 */
	protected void checkForAliasCircle(String name, String alias) {
		//以别名当作名称查找, 如果能找到则表是有循环引用
		if (hasAlias(alias, name)) {
			throw new IllegalStateException("Cannot register alias '" + alias +
					"' for name '" + name + "': Circular reference - '" +
					name + "' is a direct or indirect alias for '" + alias + "' already");
		}
	}

	/**
	 * 获取真正的名称, alias1 -> alias2 -> name
	 * - 在最后的, 才是真正的名称
	 * Determine the raw name, resolving aliases to canonical names.
	 * @param name the user-specified name
	 * @return the transformed name
	 */
	public String canonicalName(String name) {
		//缓存真正的名称
		String canonicalName = name;
		// Handle aliasing...
		String resolvedName;
		do {
			//以当前 name 作为别名查询名称
			resolvedName = this.aliasMap.get(canonicalName);
			//如果找到名称
			if (resolvedName != null) {
				//则前一个 canonicalName 是别名, 替换
				canonicalName = resolvedName;
			}
		}
		//一直往下找, 直接找不到名称的前一个作为真正的名称返回
		while (resolvedName != null);
		return canonicalName;
	}

}
