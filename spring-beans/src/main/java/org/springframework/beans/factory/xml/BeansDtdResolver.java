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

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.xml.sax.EntityResolver;
import org.xml.sax.InputSource;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.lang.Nullable;

/**
 * Spring Bean dtd 解码器, 用来从 classpath 或者 jar 文件中加载 dtd.
 * {@link EntityResolver} implementation for the Spring beans DTD,
 * to load the DTD from the Spring class path (or JAR file).
 *
 * <p>Fetches "spring-beans.dtd" from the class path resource
 * "/org/springframework/beans/factory/xml/spring-beans.dtd",
 * no matter whether specified as some local URL that includes "spring-beans"
 * in the DTD name or as "https://www.springframework.org/dtd/spring-beans-2.0.dtd".
 *
 * @author Juergen Hoeller
 * @author Colin Sampaleanu
 * @since 04.06.2003
 * @see ResourceEntityResolver
 */
public class BeansDtdResolver implements EntityResolver {

	private static final String DTD_EXTENSION = ".dtd";

	private static final String DTD_NAME = "spring-beans";

	private static final Log logger = LogFactory.getLog(BeansDtdResolver.class);


	@Override
	@Nullable
	public InputSource resolveEntity(@Nullable String publicId, @Nullable String systemId) throws IOException {
		if (logger.isTraceEnabled()) {
			logger.trace("Trying to resolve XML entity with public ID [" + publicId +
					"] and system ID [" + systemId + "]");
		}

		//https://www.springframework.org/dtd/spring-beans-2.0.dtd
		//如果 systemId 不为 null, 且 systemId 以 .dtd 结尾
		if (systemId != null && systemId.endsWith(DTD_EXTENSION)) {
			//获取最后一个 / 的索引
			int lastPathSeparator = systemId.lastIndexOf('/');
			//获取 dtd 的名称的索引
			int dtdNameStart = systemId.indexOf(DTD_NAME, lastPathSeparator);
			//如果索引不等于 -1
			if (dtdNameStart != -1) {
				// 拼接名称后缀
				String dtdFile = DTD_NAME + DTD_EXTENSION;
				if (logger.isTraceEnabled()) {
					logger.trace("Trying to locate [" + dtdFile + "] in Spring jar on classpath");
				}
				try {
					//创建 ClassPathResource , 因为 spring-beans.dtd 就是当前类的同样的的包中, 只不过是在 resources 声明的
					Resource resource = new ClassPathResource(dtdFile, getClass());
					//创建 InputSource
					InputSource source = new InputSource(resource.getInputStream());
					//设置 publicId
					source.setPublicId(publicId);
					//设置 systemId
					source.setSystemId(systemId);
					if (logger.isTraceEnabled()) {
						logger.trace("Found beans DTD [" + systemId + "] in classpath: " + dtdFile);
					}
					//返回 InputSource
					return source;
				}
				catch (FileNotFoundException ex) {
					if (logger.isDebugEnabled()) {
						logger.debug("Could not resolve beans DTD [" + systemId + "]: not found in classpath", ex);
					}
				}
			}
		}

		//使用默认行为，从网络上下载
		// Fall back to the parser's default behavior.
		return null;
	}


	@Override
	public String toString() {
		return "EntityResolver for spring-beans DTD";
	}

}
