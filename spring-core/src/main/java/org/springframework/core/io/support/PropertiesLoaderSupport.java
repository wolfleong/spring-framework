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

package org.springframework.core.io.support;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.UnknownHostException;
import java.util.Properties;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.core.io.Resource;
import org.springframework.lang.Nullable;
import org.springframework.util.CollectionUtils;
import org.springframework.util.DefaultPropertiesPersister;
import org.springframework.util.PropertiesPersister;

/**
 * 抽像类, 提供从 properties 文件中读取配置的能力, 该类的属性locations指定需要加载的文件所在的路径
 * Base class for JavaBean-style components that need to load properties
 * from one or more resources. Supports local properties as well, with
 * configurable overriding.
 *
 * @author Juergen Hoeller
 * @since 1.2.2
 */
public abstract class PropertiesLoaderSupport {

	/** Logger available to subclasses. */
	protected final Log logger = LogFactory.getLog(getClass());

	/**
	 * 本地资源例如从 xml 加载的配置信息等
	 */
	@Nullable
	protected Properties[] localProperties;

	/**
	 * 为 true 则 localProperties 会覆盖从 locations 获取的属性值
	 */
	protected boolean localOverride = false;

	/**
	 * 配置文件Properties地址
	 */
	@Nullable
	private Resource[] locations;

	/**
	 * 当解析不到对应的配置文件是否报错
	 */
	private boolean ignoreResourceNotFound = false;

	@Nullable
	private String fileEncoding;

	/**
	 * Properties文件加载策略,可以配置加载编码形式等. 默认为DefaultPropertiesPersister,实质委托Properties.load 或者 Properties.store
	 */
	private PropertiesPersister propertiesPersister = new DefaultPropertiesPersister();


	/**
	 * Set local properties, e.g. via the "props" tag in XML bean definitions.
	 * These can be considered defaults, to be overridden by properties
	 * loaded from files.
	 */
	public void setProperties(Properties properties) {
		this.localProperties = new Properties[] {properties};
	}

	/**
	 * Set local properties, e.g. via the "props" tag in XML bean definitions,
	 * allowing for merging multiple properties sets into one.
	 */
	public void setPropertiesArray(Properties... propertiesArray) {
		this.localProperties = propertiesArray;
	}

	/**
	 * Set a location of a properties file to be loaded.
	 * <p>Can point to a classic properties file or to an XML file
	 * that follows JDK 1.5's properties XML format.
	 */
	public void setLocation(Resource location) {
		this.locations = new Resource[] {location};
	}

	/**
	 * Set locations of properties files to be loaded.
	 * <p>Can point to classic properties files or to XML files
	 * that follow JDK 1.5's properties XML format.
	 * <p>Note: Properties defined in later files will override
	 * properties defined earlier files, in case of overlapping keys.
	 * Hence, make sure that the most specific files are the last
	 * ones in the given list of locations.
	 */
	public void setLocations(Resource... locations) {
		this.locations = locations;
	}

	/**
	 * Set whether local properties override properties from files.
	 * <p>Default is "false": Properties from files override local defaults.
	 * Can be switched to "true" to let local properties override defaults
	 * from files.
	 */
	public void setLocalOverride(boolean localOverride) {
		this.localOverride = localOverride;
	}

	/**
	 * Set if failure to find the property resource should be ignored.
	 * <p>"true" is appropriate if the properties file is completely optional.
	 * Default is "false".
	 */
	public void setIgnoreResourceNotFound(boolean ignoreResourceNotFound) {
		this.ignoreResourceNotFound = ignoreResourceNotFound;
	}

	/**
	 * Set the encoding to use for parsing properties files.
	 * <p>Default is none, using the {@code java.util.Properties}
	 * default encoding.
	 * <p>Only applies to classic properties files, not to XML files.
	 * @see org.springframework.util.PropertiesPersister#load
	 */
	public void setFileEncoding(String encoding) {
		this.fileEncoding = encoding;
	}

	/**
	 * Set the PropertiesPersister to use for parsing properties files.
	 * The default is DefaultPropertiesPersister.
	 * @see org.springframework.util.DefaultPropertiesPersister
	 */
	public void setPropertiesPersister(@Nullable PropertiesPersister propertiesPersister) {
		this.propertiesPersister =
				(propertiesPersister != null ? propertiesPersister : new DefaultPropertiesPersister());
	}


	/**
	 * 加载和合并所有的Properties属性, 可动态控制 配置文件属性 和 BeanDefinition属性(默认配置) 加载顺序
	 * Return a merged Properties instance containing both the
	 * loaded properties and properties set on this FactoryBean.
	 */
	protected Properties mergeProperties() throws IOException {
		//记录结果用的
		Properties result = new Properties();

		//如果 localOverride 为 true, 即先加载 locations 的资源
		if (this.localOverride) {
			// Load properties from file upfront, to let local properties override.
			loadProperties(result);
		}

		//如果 localProperties 不为 null, 即 xml 配置的 kv 不为 null
		if (this.localProperties != null) {
			//遍历
			for (Properties localProp : this.localProperties) {
				//复盖 localProp 到 Result 中
				CollectionUtils.mergePropertiesIntoMap(localProp, result);
			}
		}

		//如果不允许本地复盖, 则用 locations 的资源配置复盖 localProperties 的配置
		if (!this.localOverride) {
			// Load properties from file afterwards, to let those properties override.
			loadProperties(result);
		}

		//返回
		return result;
	}

	/**
	 * 将配置文件信息,加载进入Properties实例
	 * Load properties into the given instance.
	 * @param props the Properties instance to load into
	 * @throws IOException in case of I/O errors
	 * @see #setLocations
	 */
	protected void loadProperties(Properties props) throws IOException {
		//如果资源文件路径不为 null
		if (this.locations != null) {
			//遍历资源文件路径
			for (Resource location : this.locations) {
				if (logger.isTraceEnabled()) {
					logger.trace("Loading properties file from " + location);
				}
				try {
					//解析文件, 并填充配置的属性值到 props 中
					PropertiesLoaderUtils.fillProperties(
							props, new EncodedResource(location, this.fileEncoding), this.propertiesPersister);
				}
				catch (FileNotFoundException | UnknownHostException ex) {
					if (this.ignoreResourceNotFound) {
						if (logger.isDebugEnabled()) {
							logger.debug("Properties resource not found: " + ex.getMessage());
						}
					}
					else {
						throw ex;
					}
				}
			}
		}
	}

}
