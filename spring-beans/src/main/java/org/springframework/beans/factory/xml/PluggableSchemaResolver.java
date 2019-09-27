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

package org.springframework.beans.factory.xml;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.xml.sax.EntityResolver;
import org.xml.sax.InputSource;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;

/**
 * 主要用于加载本地的 xsd schema 文件
 * {@link EntityResolver} implementation that attempts to resolve schema URLs into
 * local {@link ClassPathResource classpath resources} using a set of mappings files.
 *
 * <p>By default, this class will look for mapping files in the classpath using the
 * pattern: {@code META-INF/spring.schemas} allowing for multiple files to exist on
 * the classpath at any one time.
 *
 * <p>The format of {@code META-INF/spring.schemas} is a properties file where each line
 * should be of the form {@code systemId=schema-location} where {@code schema-location}
 * should also be a schema file in the classpath. Since {@code systemId} is commonly a
 * URL, one must be careful to escape any ':' characters which are treated as delimiters
 * in properties files.
 *
 * <p>The pattern for the mapping files can be overridden using the
 * {@link #PluggableSchemaResolver(ClassLoader, String)} constructor.
 *
 * @author Rob Harrop
 * @author Juergen Hoeller
 * @since 2.0
 */
public class PluggableSchemaResolver implements EntityResolver {

	/**
	 * The location of the file that defines schema mappings.
	 * Can be present in multiple JAR files.
	 */
	public static final String DEFAULT_SCHEMA_MAPPINGS_LOCATION = "META-INF/spring.schemas";


	private static final Log logger = LogFactory.getLog(PluggableSchemaResolver.class);

	@Nullable
	private final ClassLoader classLoader;

	/**
	 * Schema 映射的文件地址(保存着 systemId 与 本地 schema 文件的映射)
	 */
	private final String schemaMappingsLocation;

	//namespaceURI(systemId) 与 Schema 本地文件地址的映射集合
	/** Stores the mapping of schema URL -> local schema path. */
	@Nullable
	private volatile Map<String, String> schemaMappings;


	/**
	 * Loads the schema URL -> schema file location mappings using the default
	 * mapping file pattern "META-INF/spring.schemas".
	 * @param classLoader the ClassLoader to use for loading
	 * (can be {@code null}) to use the default ClassLoader)
	 * @see PropertiesLoaderUtils#loadAllProperties(String, ClassLoader)
	 */
	public PluggableSchemaResolver(@Nullable ClassLoader classLoader) {
		this.classLoader = classLoader;
		this.schemaMappingsLocation = DEFAULT_SCHEMA_MAPPINGS_LOCATION;
	}

	/**
	 * Loads the schema URL -> schema file location mappings using the given
	 * mapping file pattern.
	 * @param classLoader the ClassLoader to use for loading
	 * (can be {@code null}) to use the default ClassLoader)
	 * @param schemaMappingsLocation the location of the file that defines schema mappings
	 * (must not be empty)
	 * @see PropertiesLoaderUtils#loadAllProperties(String, ClassLoader)
	 */
	public PluggableSchemaResolver(@Nullable ClassLoader classLoader, String schemaMappingsLocation) {
		Assert.hasText(schemaMappingsLocation, "'schemaMappingsLocation' must not be empty");
		this.classLoader = classLoader;
		this.schemaMappingsLocation = schemaMappingsLocation;
	}


	/**
	 * 普及一下:
	 * - xmlns 的全称: xml namespace
	 * - xsi 是XML Schema Instance的缩写
	 * - xmlns="http://www.springframework.org/schema/beans" , 这个指定 xml 的元素默认(没有前缀)命名空间是 http://www.springframework.org/schema/beans
	 * - xmlns:前缀="命名空间" ,  为前缀指定命名空间 , 比如: xmlns:wl="http://wwww.wolfleong.com/abc/wl" ,
	 *   指定 wl 前缀的元素的命名空间是 http://wwww.wolfleong.com/abc/wl ,  <wl:table></wl:table>
	 * - xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" , 这个指定 xsi 的命名空间为 http://www.w3.org/2001/XMLSchema-instance ,
	 * - xsi:schemaLocation="http://www.springframework.org/schema/beans  http://www.springframework.org/schema/beans/spring-beans-3.0.xsd" 的 schemaLocation 有什么意义呢
	 *   - 定义了XML Namespace和对应的XSD（Xml Schema Definition）文档的位置的关系
	 *   - 它的值由一个或多个URI引用对组成，引用对内的两个URI之间以空白符分隔（空格和换行均可）。第一个URI是定义的XML Namespace的值，第二个URI给出Schema文档的位置
	 *
	 * <?xml version="1.0" encoding="UTF-8"?>
	 * <beans xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
	 * xmlns="http://www.springframework.org/schema/beans"
	 * xsi:schemaLocation="http://www.springframework.org/schema/beans  http://www.springframework.org/schema/beans/spring-beans-3.0.xsd">
	 * </beans>
	 *
	 * @param publicId 这个参数到时是 null
	 * @param systemId 这个 systemId 到时传入的是 'http://www.springframework.org/schema/beans/spring-beans-3.0.xsd',
	 *                    也就是 xml 文件上命名空间指定的 schema 校验文件地址
	 */
	@Override
	@Nullable
	public InputSource resolveEntity(@Nullable String publicId, @Nullable String systemId) throws IOException {
		if (logger.isTraceEnabled()) {
			logger.trace("Trying to resolve XML entity with public id [" + publicId +
					"] and system id [" + systemId + "]");
		}

		//如果 systemId 不为 null
		if (systemId != null) {
			//坐缓存中获取 Resource 所有的位置
			String resourceLocation = getSchemaMappings().get(systemId);
			//如果缓存的位置为 null, 且 systemId 是以 https: 开头的
			if (resourceLocation == null && systemId.startsWith("https:")) {
				//将 https 转成 http , 再次从缓存中获取一次
				// Retrieve canonical http schema mapping even for https declaration
				resourceLocation = getSchemaMappings().get("http:" + systemId.substring(6));
			}
			//如果有拿到
			if (resourceLocation != null) {
				//创建 ClassPathResource
				Resource resource = new ClassPathResource(resourceLocation, this.classLoader);
				try {
					//创建 InputSource
					InputSource source = new InputSource(resource.getInputStream());
					//设置
					source.setPublicId(publicId);
					source.setSystemId(systemId);
					if (logger.isTraceEnabled()) {
						logger.trace("Found XML schema [" + systemId + "] in classpath: " + resourceLocation);
					}
					return source;
				}
				catch (FileNotFoundException ex) {
					if (logger.isDebugEnabled()) {
						logger.debug("Could not find XML schema [" + systemId + "]: " + resource, ex);
					}
				}
			}
		}

		// Fall back to the parser's default behavior.
		return null;
	}

	/**
	 * Load the specified schema mappings lazily.
	 */
	private Map<String, String> getSchemaMappings() {
		//双重检查锁，实现 schemaMappings 单例
		Map<String, String> schemaMappings = this.schemaMappings;
		if (schemaMappings == null) {
			synchronized (this) {
				schemaMappings = this.schemaMappings;
				if (schemaMappings == null) {
					if (logger.isTraceEnabled()) {
						logger.trace("Loading schema mappings from [" + this.schemaMappingsLocation + "]");
					}
					try {
						//从 schemaMappingsLocation 的配置文件中加载映射
						Properties mappings =
								PropertiesLoaderUtils.loadAllProperties(this.schemaMappingsLocation, this.classLoader);
						if (logger.isTraceEnabled()) {
							logger.trace("Loaded schema mappings: " + mappings);
						}
						//初始化 schemaMappings
						schemaMappings = new ConcurrentHashMap<>(mappings.size());
						//将 properties 合并到 map 中
						CollectionUtils.mergePropertiesIntoMap(mappings, schemaMappings);
						//设置全局的值
						this.schemaMappings = schemaMappings;
					}
					catch (IOException ex) {
						throw new IllegalStateException(
								"Unable to load schema mappings from location [" + this.schemaMappingsLocation + "]", ex);
					}
				}
			}
		}
		//返回 schemaMappings
		return schemaMappings;
	}


	@Override
	public String toString() {
		return "EntityResolver using schema mappings " + getSchemaMappings();
	}

}
