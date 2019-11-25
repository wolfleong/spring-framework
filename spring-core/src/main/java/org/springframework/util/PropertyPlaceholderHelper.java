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

package org.springframework.util;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.lang.Nullable;

/**
 * 属性占位符内容解析工具类
 * - 持有占位符的前缀、后缀、多值的分隔符，负责把占位符的字符串去除前缀、后缀. 调用 PropertyPlaceholderConfigurerResolver 进行字符串替换.
 * Utility class for working with Strings that have placeholder values in them. A placeholder takes the form
 * {@code ${name}}. Using {@code PropertyPlaceholderHelper} these placeholders can be substituted for
 * user-supplied values. <p> Values for substitution can be supplied using a {@link Properties} instance or
 * using a {@link PlaceholderResolver}.
 *
 * @author Juergen Hoeller
 * @author Rob Harrop
 * @since 3.0
 */
public class PropertyPlaceholderHelper {

	private static final Log logger = LogFactory.getLog(PropertyPlaceholderHelper.class);

	private static final Map<String, String> wellKnownSimplePrefixes = new HashMap<>(4);

	static {
		wellKnownSimplePrefixes.put("}", "{");
		wellKnownSimplePrefixes.put("]", "[");
		wellKnownSimplePrefixes.put(")", "(");
	}


	/**
	 * 占位符前缀
	 */
	private final String placeholderPrefix;

	/**
	 * 占位符后缀
	 */
	private final String placeholderSuffix;

	private final String simplePrefix;

	/**
	 * 默认值符割符
	 */
	@Nullable
	private final String valueSeparator;

	/**
	 * 是否忽略不能解析成功的占位符, 如果为 false , 遇到不能解析的占位符, 则报错
	 */
	private final boolean ignoreUnresolvablePlaceholders;


	/**
	 * Creates a new {@code PropertyPlaceholderHelper} that uses the supplied prefix and suffix.
	 * Unresolvable placeholders are ignored.
	 * @param placeholderPrefix the prefix that denotes the start of a placeholder
	 * @param placeholderSuffix the suffix that denotes the end of a placeholder
	 */
	public PropertyPlaceholderHelper(String placeholderPrefix, String placeholderSuffix) {
		this(placeholderPrefix, placeholderSuffix, null, true);
	}

	/**
	 * Creates a new {@code PropertyPlaceholderHelper} that uses the supplied prefix and suffix.
	 * @param placeholderPrefix the prefix that denotes the start of a placeholder
	 * @param placeholderSuffix the suffix that denotes the end of a placeholder
	 * @param valueSeparator the separating character between the placeholder variable
	 * and the associated default value, if any
	 * @param ignoreUnresolvablePlaceholders indicates whether unresolvable placeholders should
	 * be ignored ({@code true}) or cause an exception ({@code false})
	 */
	public PropertyPlaceholderHelper(String placeholderPrefix, String placeholderSuffix,
			@Nullable String valueSeparator, boolean ignoreUnresolvablePlaceholders) {

		Assert.notNull(placeholderPrefix, "'placeholderPrefix' must not be null");
		Assert.notNull(placeholderSuffix, "'placeholderSuffix' must not be null");
		//前缀
		this.placeholderPrefix = placeholderPrefix;
		//后缀
		this.placeholderSuffix = placeholderSuffix;
		//根据后缀获取简单前缀
		String simplePrefixForSuffix = wellKnownSimplePrefixes.get(this.placeholderSuffix);
		//如果简单前缀不为空, 则给定前缀是以简单后缀结束的
		if (simplePrefixForSuffix != null && this.placeholderPrefix.endsWith(simplePrefixForSuffix)) {
			//缓存这个简单前缀
			this.simplePrefix = simplePrefixForSuffix;
		}
		//如果没有简单前缀的话
		else {
			//直接使用配置的前缀
			this.simplePrefix = this.placeholderPrefix;
		}
		this.valueSeparator = valueSeparator;
		this.ignoreUnresolvablePlaceholders = ignoreUnresolvablePlaceholders;
	}


	/**
	 * Replaces all placeholders of format {@code ${name}} with the corresponding
	 * property from the supplied {@link Properties}.
	 * @param value the value containing the placeholders to be replaced
	 * @param properties the {@code Properties} to use for replacement
	 * @return the supplied value with placeholders replaced inline
	 */
	public String replacePlaceholders(String value, final Properties properties) {
		Assert.notNull(properties, "'properties' must not be null");
		return replacePlaceholders(value, properties::getProperty);
	}

	/**
	 * Replaces all placeholders of format {@code ${name}} with the value returned
	 * from the supplied {@link PlaceholderResolver}.
	 * @param value the value containing the placeholders to be replaced
	 * @param placeholderResolver the {@code PlaceholderResolver} to use for replacement
	 * @return the supplied value with placeholders replaced inline
	 */
	public String replacePlaceholders(String value, PlaceholderResolver placeholderResolver) {
		Assert.notNull(value, "'value' must not be null");
		return parseStringValue(value, placeholderResolver, null);
	}

	protected String parseStringValue(
			String value, PlaceholderResolver placeholderResolver, @Nullable Set<String> visitedPlaceholders) {

		//获取占位符前缀索引
		int startIndex = value.indexOf(this.placeholderPrefix);
		//如果没有占位符前缀的索引, 则直接返回
		if (startIndex == -1) {
			return value;
		}

		//创建 StringBuilder
		StringBuilder result = new StringBuilder(value);
		//如果字符串有前缀索引, 则循环解析
		while (startIndex != -1) {
			//获取占位符后缀索引
			int endIndex = findPlaceholderEndIndex(result, startIndex);
			//如果有后缀索引, 则处理
			if (endIndex != -1) {
				//获取占位符之前的内容
				String placeholder = result.substring(startIndex + this.placeholderPrefix.length(), endIndex);
				//
				String originalPlaceholder = placeholder;
				//如果 visitedPlaceholders 为 null, 则初始化 visitedPlaceholders
				if (visitedPlaceholders == null) {
					visitedPlaceholders = new HashSet<>(4);
				}
				//添加当前正在解析的占位符属性到 visitedPlaceholders 中, 添加不成功则报错, 感觉这里的作用是避免重复解析?
				if (!visitedPlaceholders.add(originalPlaceholder)) {
					throw new IllegalArgumentException(
							"Circular placeholder reference '" + originalPlaceholder + "' in property definitions");
				}
				//递归解析, 直到没有占位符为止
				// Recursive invocation, parsing placeholders contained in the placeholder key.
				placeholder = parseStringValue(placeholder, placeholderResolver, visitedPlaceholders);
				//解析占位符的真正内容
				// Now obtain the value for the fully resolved key...
				String propVal = placeholderResolver.resolvePlaceholder(placeholder);
				//如果占位符没有属性值, 且默认值分割符不为 null
				if (propVal == null && this.valueSeparator != null) {
					//获取分割符的位置
					int separatorIndex = placeholder.indexOf(this.valueSeparator);
					//如果默认分割符索引存在
					if (separatorIndex != -1) {
						//截取真正的占位符属性
						String actualPlaceholder = placeholder.substring(0, separatorIndex);
						//获取占位符的默认值
						String defaultValue = placeholder.substring(separatorIndex + this.valueSeparator.length());
						//解析占位符的真正值
						propVal = placeholderResolver.resolvePlaceholder(actualPlaceholder);
						//如果值为 null
						if (propVal == null) {
							//使用默认值作为解析结果
							propVal = defaultValue;
						}
					}
				}
				//如果解析出占位符真正的值
				if (propVal != null) {
					//将解析出来的属性值, 继续解析, 这里表明了为什么 application.yml 里可以配置上下文变量, 如:  name=123, myname=${name}456
					// Recursive invocation, parsing placeholders contained in the
					// previously resolved placeholder value.
					propVal = parseStringValue(propVal, placeholderResolver, visitedPlaceholders);
					//替换掉解析完成后的属性值
					result.replace(startIndex, endIndex + this.placeholderSuffix.length(), propVal);
					if (logger.isTraceEnabled()) {
						logger.trace("Resolved placeholder '" + placeholder + "'");
					}
					//获取下一个占位符前缀索引
					startIndex = result.indexOf(this.placeholderPrefix, startIndex + propVal.length());
				}
				//如果没有找到, 且可以忽略未解析成功的占位符
				else if (this.ignoreUnresolvablePlaceholders) {
					//获取下一个未解析的占位符前缀索引
					// Proceed with unprocessed value.
					startIndex = result.indexOf(this.placeholderPrefix, endIndex + this.placeholderSuffix.length());
				}
				//如果必须要解析占位符, 且没解析成功, 则报错
				else {
					throw new IllegalArgumentException("Could not resolve placeholder '" +
							placeholder + "'" + " in value \"" + value + "\"");
				}
				//删除已经解析完成的占位符
				visitedPlaceholders.remove(originalPlaceholder);
			}
			//如果没有后缀索引
			else {
				//设置前缀索引为 -1 , 方便直接退出
				startIndex = -1;
			}
		}
		//返回替换的结果
		return result.toString();
	}

	/**
	 * 获取指定前缀索引对应的后缀索引, 会有主要两种种情况, 如下:
	 * - ${abc${a${234}bc}bc}
	 * - ${133}
	 *
	 *  查找与PREFIX配对的SUFFIX
	 *  注意处理嵌套的情况：用 withinNestedPlaceholder 变量来记录
	 *  例如${ab${cd}}，startIndex指向a，从a开始找，当找到"${"时，within为1；
	 *  找到第一个"}"时，withinNestedPlaceholder 减1，抵消前面的"${"；while循环继续，直到找到最后的"}"
	 *  这有点像"利用栈来判断括号是否配对"
	 *
	 * 注意: 每一个前缀肯定要对应一个后缀
	 *
	 * @param buf 字符串
	 * @param startIndex 前缀索引的位置
	 */
	private int findPlaceholderEndIndex(CharSequence buf, int startIndex) {
		//前缀索引之后的第一个索引, 也就是要判断的索引
		int index = startIndex + this.placeholderPrefix.length();
		//嵌套占位符层数
		int withinNestedPlaceholder = 0;
		//如果 index 小于字符串的长度, 即表明 index 后面还有字符
		while (index < buf.length()) {
			//在 index 索引位置匹配后缀字符串, 如果匹配上的话
			if (StringUtils.substringMatch(buf, index, this.placeholderSuffix)) {
				//如果有嵌套占位符层数大于 0 , 每一层有一个结束后缀
				if (withinNestedPlaceholder > 0) {
					//遇到一个结束符, 则减掉一层
					withinNestedPlaceholder--;
					//获取后缀索引后面的索引
					index = index + this.placeholderSuffix.length();
				}
				//如果没有嵌套, 则当前后缀索引就是对应前给定的前缀索引
				else {
					return index;
				}
			}
			//这里为什么可以用简单前缀呢, 主要是嵌套内容的占位符在外层还会做真正的解析,
			//在 index 索引位置匹配简单前缀字符, 如果匹配上的话
			else if (StringUtils.substringMatch(buf, index, this.simplePrefix)) {
				//占位符嵌套层数加 1
				withinNestedPlaceholder++;
				//获取前缀后面的索引
				index = index + this.simplePrefix.length();
			}
			//字符索引加1 , 也就是处理下一个索引
			else {
				index++;
			}
		}
		//如果没找到, 则直接返回
		return -1;
	}


	/**
	 * Strategy interface used to resolve replacement values for placeholders contained in Strings.
	 */
	@FunctionalInterface
	public interface PlaceholderResolver {

		/**
		 * Resolve the supplied placeholder name to the replacement value.
		 * @param placeholderName the name of the placeholder to resolve
		 * @return the replacement value, or {@code null} if no replacement is to be made
		 */
		@Nullable
		String resolvePlaceholder(String placeholderName);
	}

}
