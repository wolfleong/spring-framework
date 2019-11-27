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

package org.springframework.beans.factory.config;

import java.util.Properties;

import org.springframework.beans.BeansException;
import org.springframework.core.Constants;
import org.springframework.core.SpringProperties;
import org.springframework.core.env.AbstractEnvironment;
import org.springframework.lang.Nullable;
import org.springframework.util.PropertyPlaceholderHelper;
import org.springframework.util.PropertyPlaceholderHelper.PlaceholderResolver;
import org.springframework.util.StringValueResolver;

/**
 * 允许我们用 Properties 文件中的属性或系统变量来替换 bean实体中占位符
 * - 这个类是要在 xml 配置的才可以使用的, 不懂的话可以查询一下它的用法
 * - 从properties中将传入占位符替换为对应的值
 * - 这个已经过期了, 建议用 PropertySourcesPlaceholderConfigurer 来代替
 * {@link PlaceholderConfigurerSupport} subclass that resolves ${...} placeholders against
 * {@link #setLocation local} {@link #setProperties properties} and/or system properties
 * and environment variables.
 *
 * <p>{@link PropertyPlaceholderConfigurer} is still appropriate for use when:
 * <ul>
 * <li>the {@code spring-context} module is not available (i.e., one is using Spring's
 * {@code BeanFactory} API as opposed to {@code ApplicationContext}).
 * <li>existing configuration makes use of the {@link #setSystemPropertiesMode(int) "systemPropertiesMode"}
 * and/or {@link #setSystemPropertiesModeName(String) "systemPropertiesModeName"} properties.
 * Users are encouraged to move away from using these settings, and rather configure property
 * source search order through the container's {@code Environment}; however, exact preservation
 * of functionality may be maintained by continuing to use {@code PropertyPlaceholderConfigurer}.
 * </ul>
 *
 * @author Juergen Hoeller
 * @author Chris Beams
 * @since 02.10.2003
 * @see #setSystemPropertiesModeName
 * @see PlaceholderConfigurerSupport
 * @see PropertyOverrideConfigurer
 * @deprecated as of 5.2; use {@code org.springframework.context.support.PropertySourcesPlaceholderConfigurer}
 * instead which is more flexible through taking advantage of the {@link org.springframework.core.env.Environment}
 * and {@link org.springframework.core.env.PropertySource} mechanisms.
 */
@Deprecated
public class PropertyPlaceholderConfigurer extends PlaceholderConfigurerSupport {

	//以配置文件为准, 不会加载 JVM 系统变量和系统环境变量
	/** Never check system properties. */
	public static final int SYSTEM_PROPERTIES_MODE_NEVER = 0;

	/**
	 * 此时以配置文件优先, 加载不到会再次load JVM系统变量和系统环境变量
	 * Check system properties if not resolvable in the specified properties.
	 * This is the default.
	 */
	public static final int SYSTEM_PROPERTIES_MODE_FALLBACK = 1;

	/**
	 * 此时以JVM系统变量和系统环境变量优先, 加载不到会再次load 配置文件变量
	 * Check system properties first, before trying the specified properties.
	 * This allows system properties to override any other property source.
	 */
	public static final int SYSTEM_PROPERTIES_MODE_OVERRIDE = 2;


	/**
	 * spring提供工具类, 利用jdk反射机制, 对类常量(public static final)进行映射, 可通过常量名称进行访问.
	 */
	private static final Constants constants = new Constants(PropertyPlaceholderConfigurer.class);

	/**
	 * 默认是 1, 优先配置文件, 再到 jvm 系统变量和系统环境变量
	 */
	private int systemPropertiesMode = SYSTEM_PROPERTIES_MODE_FALLBACK;

	/**
	 * 控制是否会加载系统环境变量
	 */
	private boolean searchSystemEnvironment =
			!SpringProperties.getFlag(AbstractEnvironment.IGNORE_GETENV_PROPERTY_NAME);


	/**
	 * Set the system property mode by the name of the corresponding constant,
	 * e.g. "SYSTEM_PROPERTIES_MODE_OVERRIDE".
	 * @param constantName name of the constant
	 * @see #setSystemPropertiesMode
	 */
	public void setSystemPropertiesModeName(String constantName) throws IllegalArgumentException {
		this.systemPropertiesMode = constants.asNumber(constantName).intValue();
	}

	/**
	 * Set how to check system properties: as fallback, as override, or never.
	 * For example, will resolve ${user.dir} to the "user.dir" system property.
	 * <p>The default is "fallback": If not being able to resolve a placeholder
	 * with the specified properties, a system property will be tried.
	 * "override" will check for a system property first, before trying the
	 * specified properties. "never" will not check system properties at all.
	 * @see #SYSTEM_PROPERTIES_MODE_NEVER
	 * @see #SYSTEM_PROPERTIES_MODE_FALLBACK
	 * @see #SYSTEM_PROPERTIES_MODE_OVERRIDE
	 * @see #setSystemPropertiesModeName
	 */
	public void setSystemPropertiesMode(int systemPropertiesMode) {
		this.systemPropertiesMode = systemPropertiesMode;
	}

	/**
	 * Set whether to search for a matching system environment variable
	 * if no matching system property has been found. Only applied when
	 * "systemPropertyMode" is active (i.e. "fallback" or "override"), right
	 * after checking JVM system properties.
	 * <p>Default is "true". Switch this setting off to never resolve placeholders
	 * against system environment variables. Note that it is generally recommended
	 * to pass external values in as JVM system properties: This can easily be
	 * achieved in a startup script, even for existing environment variables.
	 * @see #setSystemPropertiesMode
	 * @see System#getProperty(String)
	 * @see System#getenv(String)
	 */
	public void setSearchSystemEnvironment(boolean searchSystemEnvironment) {
		this.searchSystemEnvironment = searchSystemEnvironment;
	}

	/**
	 * 依据systemPropertiesMode配置的策略, 根据占位符名称换取对应的值
	 * Resolve the given placeholder using the given properties, performing
	 * a system properties check according to the given mode.
	 * <p>The default implementation delegates to {@code resolvePlaceholder
	 * (placeholder, props)} before/after the system properties check.
	 * <p>Subclasses can override this for custom resolution strategies,
	 * including customized points for the system properties check.
	 * @param placeholder the placeholder to resolve
	 * @param props the merged properties of this configurer
	 * @param systemPropertiesMode the system properties mode,
	 * according to the constants in this class
	 * @return the resolved value, of null if none
	 * @see #setSystemPropertiesMode
	 * @see System#getProperty
	 * @see #resolvePlaceholder(String, java.util.Properties)
	 */
	@Nullable
	protected String resolvePlaceholder(String placeholder, Properties props, int systemPropertiesMode) {
		String propVal = null;
		//如果是系统变量优先
		if (systemPropertiesMode == SYSTEM_PROPERTIES_MODE_OVERRIDE) {
			//先用系统变量将占位符上的内容替换掉
			propVal = resolveSystemProperty(placeholder);
		}
		//如果没有替换
		if (propVal == null) {
			//则用配置文件的内容替换占位符
			propVal = resolvePlaceholder(placeholder, props);
		}
		//如果还是为空, 且是以配置文件优先, 则尝试系统变量替换
		if (propVal == null && systemPropertiesMode == SYSTEM_PROPERTIES_MODE_FALLBACK) {
			propVal = resolveSystemProperty(placeholder);
		}
		//返回替换好的值
		return propVal;
	}

	/**
	 * 用配置文件的值替换占位符内容
	 * Resolve the given placeholder using the given properties.
	 * The default implementation simply checks for a corresponding property key.
	 * <p>Subclasses can override this for customized placeholder-to-key mappings
	 * or custom resolution strategies, possibly just using the given properties
	 * as fallback.
	 * <p>Note that system properties will still be checked before respectively
	 * after this method is invoked, according to the system properties mode.
	 * @param placeholder the placeholder to resolve
	 * @param props the merged properties of this configurer
	 * @return the resolved value, of {@code null} if none
	 * @see #setSystemPropertiesMode
	 */
	@Nullable
	protected String resolvePlaceholder(String placeholder, Properties props) {
		return props.getProperty(placeholder);
	}

	/**
	 * 用系统变量或系统环境变量替换占位符内容
	 * Resolve the given key as JVM system property, and optionally also as
	 * system environment variable if no matching system property has been found.
	 * @param key the placeholder to resolve as system property key
	 * @return the system property value, or {@code null} if not found
	 * @see #setSearchSystemEnvironment
	 * @see System#getProperty(String)
	 * @see System#getenv(String)
	 */
	@Nullable
	protected String resolveSystemProperty(String key) {
		try {
			//先获取系统变量
			String value = System.getProperty(key);
			//如果系统变量为 null, 且查询系统环境变量
			if (value == null && this.searchSystemEnvironment) {
				//获取系统环境变量
				value = System.getenv(key);
			}
			return value;
		}
		catch (Throwable ex) {
			if (logger.isDebugEnabled()) {
				logger.debug("Could not access system property '" + key + "': " + ex);
			}
			return null;
		}
	}


	/**
	 * 重写 PlaceholderConfigurerSupport 抽象方法 processProperties, 实现替换逻辑
	 * Visit each bean definition in the given bean factory and attempt to replace ${...} property
	 * placeholders with values from the given properties.
	 */
	@Override
	protected void processProperties(ConfigurableListableBeanFactory beanFactoryToProcess, Properties props)
			throws BeansException {

		//创建 PlaceholderResolvingStringValueResolver
		StringValueResolver valueResolver = new PlaceholderResolvingStringValueResolver(props);
		//调用父类的方法, 进行替换
		doProcessProperties(beanFactoryToProcess, valueResolver);
	}


	/**
	 * 实际替换占位符的模版类, 实现在字符串解析接口 StringValueResolver
	 * - 这个实现类主要做了占位符解析
	 */
	private class PlaceholderResolvingStringValueResolver implements StringValueResolver {

		private final PropertyPlaceholderHelper helper;

		private final PlaceholderResolver resolver;

		public PlaceholderResolvingStringValueResolver(Properties props) {
			//创建 PropertyPlaceholderHelper
			this.helper = new PropertyPlaceholderHelper(
					placeholderPrefix, placeholderSuffix, valueSeparator, ignoreUnresolvablePlaceholders);
			//创建 PropertyPlaceholderConfigurerResolver
			this.resolver = new PropertyPlaceholderConfigurerResolver(props);
		}

		@Override
		@Nullable
		public String resolveStringValue(String strVal) throws BeansException {
			//用 PropertyPlaceholderHelper 解析字符串
			String resolved = this.helper.replacePlaceholders(strVal, this.resolver);
			//如果需要去前后空格, 则调用 trim()
			if (trimValues) {
				resolved = resolved.trim();
			}
			//如果是空值字符串, 则返回 null, 否则返回解析后的值
			return (resolved.equals(nullValue) ? null : resolved);
		}
	}


	/**
	 * 实现 PlaceholderResolver 接口, 占位符解析类, 实际操作是委托给 PropertyPlaceholderConfigurer.resolvePlaceholder() 方法
	 * - 这个接口主要做了将占位值替换
	 */
	private final class PropertyPlaceholderConfigurerResolver implements PlaceholderResolver {

		private final Properties props;

		private PropertyPlaceholderConfigurerResolver(Properties props) {
			this.props = props;
		}

		@Override
		@Nullable
		public String resolvePlaceholder(String placeholderName) {
			return PropertyPlaceholderConfigurer.this.resolvePlaceholder(placeholderName,
					this.props, systemPropertiesMode);
		}
	}

}
