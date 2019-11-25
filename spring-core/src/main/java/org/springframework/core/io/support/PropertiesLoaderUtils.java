/*
 * Copyright 2002-2016 the original author or authors.
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

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.net.URL;
import java.net.URLConnection;
import java.util.Enumeration;
import java.util.Properties;

import org.springframework.core.io.Resource;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.DefaultPropertiesPersister;
import org.springframework.util.PropertiesPersister;
import org.springframework.util.ResourceUtils;

/**
 * Convenient utility methods for loading of {@code java.util.Properties},
 * performing standard handling of input streams.
 *
 * <p>For more configurable properties loading, including the option of a
 * customized encoding, consider using the PropertiesLoaderSupport class.
 *
 * @author Juergen Hoeller
 * @author Rob Harrop
 * @since 2.0
 * @see PropertiesLoaderSupport
 */
public abstract class PropertiesLoaderUtils {

	private static final String XML_FILE_EXTENSION = ".xml";


	/**
	 * Load properties from the given EncodedResource,
	 * potentially defining a specific encoding for the properties file.
	 * @see #fillProperties(java.util.Properties, EncodedResource)
	 */
	public static Properties loadProperties(EncodedResource resource) throws IOException {
		Properties props = new Properties();
		fillProperties(props, resource);
		return props;
	}

	/**
	 * Fill the given properties from the given EncodedResource,
	 * potentially defining a specific encoding for the properties file.
	 * @param props the Properties instance to load into
	 * @param resource the resource to load from
	 * @throws IOException in case of I/O errors
	 */
	public static void fillProperties(Properties props, EncodedResource resource)
			throws IOException {

		fillProperties(props, resource, new DefaultPropertiesPersister());
	}

	/**
	 * 加载配置文件到 props 的真正实现
	 * Actually load properties from the given EncodedResource into the given Properties instance.
	 * @param props the Properties instance to load into
	 * @param resource the resource to load from
	 * @param persister the PropertiesPersister to use
	 * @throws IOException in case of I/O errors
	 */
	static void fillProperties(Properties props, EncodedResource resource, PropertiesPersister persister)
			throws IOException {

		InputStream stream = null;
		Reader reader = null;
		try {
			//获取资源文件名
			String filename = resource.getResource().getFilename();
			//如果文件名不为 null 且是 .xml 结尾的, 也就是 xml 文件
			if (filename != null && filename.endsWith(XML_FILE_EXTENSION)) {
				//获取文件输入流
				stream = resource.getInputStream();
				//用 PropertiesPersister 加载 xml 的 kv 配置
				persister.loadFromXml(props, stream);
			}
			//如果有字符编码, 则走字符流
			else if (resource.requiresReader()) {
				reader = resource.getReader();
				persister.load(props, reader);
			}
			else {
				//配置走字节流解析
				stream = resource.getInputStream();
				persister.load(props, stream);
			}
		}
		finally {
			//最后关流
			if (stream != null) {
				stream.close();
			}
			if (reader != null) {
				reader.close();
			}
		}
	}

	/**
	 * Load properties from the given resource (in ISO-8859-1 encoding).
	 * @param resource the resource to load from
	 * @return the populated Properties instance
	 * @throws IOException if loading failed
	 * @see #fillProperties(java.util.Properties, Resource)
	 */
	public static Properties loadProperties(Resource resource) throws IOException {
		Properties props = new Properties();
		fillProperties(props, resource);
		return props;
	}

	/**
	 * Fill the given properties from the given resource (in ISO-8859-1 encoding).
	 * @param props the Properties instance to fill
	 * @param resource the resource to load from
	 * @throws IOException if loading failed
	 */
	public static void fillProperties(Properties props, Resource resource) throws IOException {
		InputStream is = resource.getInputStream();
		try {
			String filename = resource.getFilename();
			if (filename != null && filename.endsWith(XML_FILE_EXTENSION)) {
				props.loadFromXML(is);
			}
			else {
				props.load(is);
			}
		}
		finally {
			is.close();
		}
	}

	/**
	 * Load all properties from the specified class path resource
	 * (in ISO-8859-1 encoding), using the default class loader.
	 * <p>Merges properties if more than one resource of the same name
	 * found in the class path.
	 * @param resourceName the name of the class path resource
	 * @return the populated Properties instance
	 * @throws IOException if loading failed
	 */
	public static Properties loadAllProperties(String resourceName) throws IOException {
		return loadAllProperties(resourceName, null);
	}

	/**
	 * 从给定的路径中加载所有的 properties, 有可能有多个
	 * Load all properties from the specified class path resource
	 * (in ISO-8859-1 encoding), using the given class loader.
	 * <p>Merges properties if more than one resource of the same name
	 * found in the class path.
	 * @param resourceName the name of the class path resource
	 * @param classLoader the ClassLoader to use for loading
	 * (or {@code null} to use the default class loader)
	 * @return the populated Properties instance
	 * @throws IOException if loading failed
	 */
	public static Properties loadAllProperties(String resourceName, @Nullable ClassLoader classLoader) throws IOException {
		Assert.notNull(resourceName, "Resource name must not be null");
		ClassLoader classLoaderToUse = classLoader;
		//如果给定的 classLoader 为 null
		if (classLoaderToUse == null) {
			//获取默认的 ClassLoader
			classLoaderToUse = ClassUtils.getDefaultClassLoader();
		}
		//获取所有的资源
		Enumeration<URL> urls = (classLoaderToUse != null ? classLoaderToUse.getResources(resourceName) :
				ClassLoader.getSystemResources(resourceName));
		//创建 Properties
		Properties props = new Properties();
		//如果有更多的记录
		while (urls.hasMoreElements()) {
			//获取 url
			URL url = urls.nextElement();
			//打开连接
			URLConnection con = url.openConnection();
			//设置缓存
			ResourceUtils.useCachesIfNecessary(con);
			//打开流
			InputStream is = con.getInputStream();
			try {
				//如果是xml , 则从 xml 文件中加载
				if (resourceName.endsWith(XML_FILE_EXTENSION)) {
					props.loadFromXML(is);
				}
				else {
					//加载
					props.load(is);
				}
			}
			finally {
				//关流
				is.close();
			}
		}
		return props;
	}

}
