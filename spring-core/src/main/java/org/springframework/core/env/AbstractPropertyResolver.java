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

package org.springframework.core.env;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.core.convert.ConversionService;
import org.springframework.core.convert.support.ConfigurableConversionService;
import org.springframework.core.convert.support.DefaultConversionService;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.PropertyPlaceholderHelper;
import org.springframework.util.SystemPropertyUtils;

/**
 * ConfigurablePropertyResolver 接口的抽象类, 它实现了 ConfigurablePropertyResolver 接口的所有方法和 PropertyResolver 接口的部分方法
 * Abstract base class for resolving properties against any underlying source.
 *
 * @author Chris Beams
 * @author Juergen Hoeller
 * @since 3.1
 */
public abstract class AbstractPropertyResolver implements ConfigurablePropertyResolver {

	protected final Log logger = LogFactory.getLog(getClass());

	/**
	 * 缓存 ConfigurableConversionService, 转换服务
	 */
	@Nullable
	private volatile ConfigurableConversionService conversionService;

	/**
	 * 占位符解析工具类, 非严格
	 */
	@Nullable
	private PropertyPlaceholderHelper nonStrictHelper;

	/**
	 * 占位符解析工具类, 严格
	 */
	@Nullable
	private PropertyPlaceholderHelper strictHelper;

	/**
	 * 是否忽略不能解析的占位符, 默认是 false
	 */
	private boolean ignoreUnresolvableNestedPlaceholders = false;

	/**
	 * 占位符前缀, 默认 ${
	 */
	private String placeholderPrefix = SystemPropertyUtils.PLACEHOLDER_PREFIX;

	/**
	 * 占位符后缀, 默认 }
	 */
	private String placeholderSuffix = SystemPropertyUtils.PLACEHOLDER_SUFFIX;

	/**
	 * 默认属性分割符
	 */
	@Nullable
	private String valueSeparator = SystemPropertyUtils.VALUE_SEPARATOR;

	/**
	 * 必须存在的属性列表
	 */
	private final Set<String> requiredProperties = new LinkedHashSet<>();


	@Override
	public ConfigurableConversionService getConversionService() {
		//获取缓存的 ConfigurableConversionService
		// Need to provide an independent DefaultConversionService, not the
		// shared DefaultConversionService used by PropertySourcesPropertyResolver.
		ConfigurableConversionService cs = this.conversionService;
		//这里做了双重校验
		//如果类型转换为 null
		if (cs == null) {
			//同步处理
			synchronized (this) {
				//再次获取
				cs = this.conversionService;
				//如果 cs 为 null
				if (cs == null) {
					//创建默认的类型 转换服务
					cs = new DefaultConversionService();
					//缓存起来
					this.conversionService = cs;
				}
			}
		}
		//返回结果
		return cs;
	}

	@Override
	public void setConversionService(ConfigurableConversionService conversionService) {
		Assert.notNull(conversionService, "ConversionService must not be null");
		this.conversionService = conversionService;
	}

	/**
	 * Set the prefix that placeholders replaced by this resolver must begin with.
	 * <p>The default is "${".
	 * @see org.springframework.util.SystemPropertyUtils#PLACEHOLDER_PREFIX
	 */
	@Override
	public void setPlaceholderPrefix(String placeholderPrefix) {
		Assert.notNull(placeholderPrefix, "'placeholderPrefix' must not be null");
		this.placeholderPrefix = placeholderPrefix;
	}

	/**
	 * Set the suffix that placeholders replaced by this resolver must end with.
	 * <p>The default is "}".
	 * @see org.springframework.util.SystemPropertyUtils#PLACEHOLDER_SUFFIX
	 */
	@Override
	public void setPlaceholderSuffix(String placeholderSuffix) {
		Assert.notNull(placeholderSuffix, "'placeholderSuffix' must not be null");
		this.placeholderSuffix = placeholderSuffix;
	}

	/**
	 * Specify the separating character between the placeholders replaced by this
	 * resolver and their associated default value, or {@code null} if no such
	 * special character should be processed as a value separator.
	 * <p>The default is ":".
	 * @see org.springframework.util.SystemPropertyUtils#VALUE_SEPARATOR
	 */
	@Override
	public void setValueSeparator(@Nullable String valueSeparator) {
		this.valueSeparator = valueSeparator;
	}

	/**
	 * Set whether to throw an exception when encountering an unresolvable placeholder
	 * nested within the value of a given property. A {@code false} value indicates strict
	 * resolution, i.e. that an exception will be thrown. A {@code true} value indicates
	 * that unresolvable nested placeholders should be passed through in their unresolved
	 * ${...} form.
	 * <p>The default is {@code false}.
	 * @since 3.2
	 */
	@Override
	public void setIgnoreUnresolvableNestedPlaceholders(boolean ignoreUnresolvableNestedPlaceholders) {
		this.ignoreUnresolvableNestedPlaceholders = ignoreUnresolvableNestedPlaceholders;
	}

	@Override
	public void setRequiredProperties(String... requiredProperties) {
		Collections.addAll(this.requiredProperties, requiredProperties);
	}

	@Override
	public void validateRequiredProperties() {
		//创建异常
		MissingRequiredPropertiesException ex = new MissingRequiredPropertiesException();
		//遍历必存的属性列表
		for (String key : this.requiredProperties) {
			//如果获取不到属性
			if (this.getProperty(key) == null) {
				//添加一个不存在的 key 到异常中
				ex.addMissingRequiredProperty(key);
			}
		}
		//如果异常的 key 列表不为空, 则抛出
		if (!ex.getMissingRequiredProperties().isEmpty()) {
			throw ex;
		}
	}

	@Override
	public boolean containsProperty(String key) {
		return (getProperty(key) != null);
	}

	@Override
	@Nullable
	public String getProperty(String key) {
		return getProperty(key, String.class);
	}

	@Override
	public String getProperty(String key, String defaultValue) {
		String value = getProperty(key);
		return (value != null ? value : defaultValue);
	}

	@Override
	public <T> T getProperty(String key, Class<T> targetType, T defaultValue) {
		T value = getProperty(key, targetType);
		return (value != null ? value : defaultValue);
	}

	@Override
	public String getRequiredProperty(String key) throws IllegalStateException {
		String value = getProperty(key);
		if (value == null) {
			throw new IllegalStateException("Required key '" + key + "' not found");
		}
		return value;
	}

	@Override
	public <T> T getRequiredProperty(String key, Class<T> valueType) throws IllegalStateException {
		T value = getProperty(key, valueType);
		if (value == null) {
			throw new IllegalStateException("Required key '" + key + "' not found");
		}
		return value;
	}

	@Override
	public String resolvePlaceholders(String text) {
		//如果 nonStrictHelper 为 null
		if (this.nonStrictHelper == null) {
			//创建一个占位符解析帮助工具
			this.nonStrictHelper = createPlaceholderHelper(true);
		}
		//执行解析
		return doResolvePlaceholders(text, this.nonStrictHelper);
	}

	@Override
	public String resolveRequiredPlaceholders(String text) throws IllegalArgumentException {
		//如果 strictHelper 为 null
		if (this.strictHelper == null) {
			//创建一个占位符解析帮助工具
			this.strictHelper = createPlaceholderHelper(false);
		}
		//解析
		return doResolvePlaceholders(text, this.strictHelper);
	}

	/**
	 * 解析 value 的嵌套占位符
	 * Resolve placeholders within the given string, deferring to the value of
	 * {@link #setIgnoreUnresolvableNestedPlaceholders} to determine whether any
	 * unresolvable placeholders should raise an exception or be ignored.
	 * <p>Invoked from {@link #getProperty} and its variants, implicitly resolving
	 * nested placeholders. In contrast, {@link #resolvePlaceholders} and
	 * {@link #resolveRequiredPlaceholders} do <i>not</i> delegate
	 * to this method but rather perform their own handling of unresolvable
	 * placeholders, as specified by each of those methods.
	 * @since 3.2
	 * @see #setIgnoreUnresolvableNestedPlaceholders
	 */
	protected String resolveNestedPlaceholders(String value) {
		return (this.ignoreUnresolvableNestedPlaceholders ?
				resolvePlaceholders(value) : resolveRequiredPlaceholders(value));
	}

	/**
	 * 创建 PropertyPlaceholderHelper
	 */
	private PropertyPlaceholderHelper createPlaceholderHelper(boolean ignoreUnresolvablePlaceholders) {
		return new PropertyPlaceholderHelper(this.placeholderPrefix, this.placeholderSuffix,
				this.valueSeparator, ignoreUnresolvablePlaceholders);
	}

	/**
	 * 真正做占位符解析操作的
	 */
	private String doResolvePlaceholders(String text, PropertyPlaceholderHelper helper) {
		return helper.replacePlaceholders(text, this::getPropertyAsRawString);
	}

	/**
	 * 转换字符串的值
	 * Convert the given value to the specified target type, if necessary.
	 * @param value the original property value
	 * @param targetType the specified target type for property retrieval
	 * @return the converted value, or the original value if no conversion
	 * is necessary
	 * @since 4.3.5
	 */
	@SuppressWarnings("unchecked")
	@Nullable
	protected <T> T convertValueIfNecessary(Object value, @Nullable Class<T> targetType) {
		//如果目标类型为 null, 直接返回 value
		if (targetType == null) {
			return (T) value;
		}
		//获取缓存的转换服务
		ConversionService conversionServiceToUse = this.conversionService;
		//如果转换服务为 null
		if (conversionServiceToUse == null) {
			//如果 value 是 targetType 类型的值, 则直接强转返回
			// Avoid initialization of shared DefaultConversionService if
			// no standard type conversion is needed in the first place...
			if (ClassUtils.isAssignableValue(targetType, value)) {
				return (T) value;
			}
			//获取单例的转换服务对象
			conversionServiceToUse = DefaultConversionService.getSharedInstance();
		}
		//执行转换操作
		return conversionServiceToUse.convert(value, targetType);
	}


	/**
	 * 根据属性名获取属性值的原始值, 不处理属性值中的占位符
	 * Retrieve the specified property as a raw String,
	 * i.e. without resolution of nested placeholders.
	 * @param key the property name to resolve
	 * @return the property value or {@code null} if none found
	 */
	@Nullable
	protected abstract String getPropertyAsRawString(String key);

}
