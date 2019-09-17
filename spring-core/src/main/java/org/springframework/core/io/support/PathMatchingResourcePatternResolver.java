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

package org.springframework.core.io.support;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.net.JarURLConnection;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLConnection;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.zip.ZipException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.UrlResource;
import org.springframework.core.io.VfsResource;
import org.springframework.lang.Nullable;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.PathMatcher;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.ResourceUtils;
import org.springframework.util.StringUtils;

/**
 * A {@link ResourcePatternResolver} implementation that is able to resolve a
 * specified resource location path into one or more matching Resources.
 * The source path may be a simple path which has a one-to-one mapping to a
 * target {@link org.springframework.core.io.Resource}, or alternatively
 * may contain the special "{@code classpath*:}" prefix and/or
 * internal Ant-style regular expressions (matched using Spring's
 * {@link org.springframework.util.AntPathMatcher} utility).
 * Both of the latter are effectively wildcards.
 *
 * <p><b>No Wildcards:</b>
 *
 * <p>In the simple case, if the specified location path does not start with the
 * {@code "classpath*:}" prefix, and does not contain a PathMatcher pattern,
 * this resolver will simply return a single resource via a
 * {@code getResource()} call on the underlying {@code ResourceLoader}.
 * Examples are real URLs such as "{@code file:C:/context.xml}", pseudo-URLs
 * such as "{@code classpath:/context.xml}", and simple unprefixed paths
 * such as "{@code /WEB-INF/context.xml}". The latter will resolve in a
 * fashion specific to the underlying {@code ResourceLoader} (e.g.
 * {@code ServletContextResource} for a {@code WebApplicationContext}).
 *
 * <p><b>Ant-style Patterns:</b>
 *
 * <p>When the path location contains an Ant-style pattern, e.g.:
 * <pre class="code">
 * /WEB-INF/*-context.xml
 * com/mycompany/**&#47;applicationContext.xml
 * file:C:/some/path/*-context.xml
 * classpath:com/mycompany/**&#47;applicationContext.xml</pre>
 * the resolver follows a more complex but defined procedure to try to resolve
 * the wildcard. It produces a {@code Resource} for the path up to the last
 * non-wildcard segment and obtains a {@code URL} from it. If this URL is
 * not a "{@code jar:}" URL or container-specific variant (e.g.
 * "{@code zip:}" in WebLogic, "{@code wsjar}" in WebSphere", etc.),
 * then a {@code java.io.File} is obtained from it, and used to resolve the
 * wildcard by walking the filesystem. In the case of a jar URL, the resolver
 * either gets a {@code java.net.JarURLConnection} from it, or manually parses
 * the jar URL, and then traverses the contents of the jar file, to resolve the
 * wildcards.
 *
 * <p><b>Implications on portability:</b>
 *
 * <p>If the specified path is already a file URL (either explicitly, or
 * implicitly because the base {@code ResourceLoader} is a filesystem one,
 * then wildcarding is guaranteed to work in a completely portable fashion.
 *
 * <p>If the specified path is a classpath location, then the resolver must
 * obtain the last non-wildcard path segment URL via a
 * {@code Classloader.getResource()} call. Since this is just a
 * node of the path (not the file at the end) it is actually undefined
 * (in the ClassLoader Javadocs) exactly what sort of a URL is returned in
 * this case. In practice, it is usually a {@code java.io.File} representing
 * the directory, where the classpath resource resolves to a filesystem
 * location, or a jar URL of some sort, where the classpath resource resolves
 * to a jar location. Still, there is a portability concern on this operation.
 *
 * <p>If a jar URL is obtained for the last non-wildcard segment, the resolver
 * must be able to get a {@code java.net.JarURLConnection} from it, or
 * manually parse the jar URL, to be able to walk the contents of the jar,
 * and resolve the wildcard. This will work in most environments, but will
 * fail in others, and it is strongly recommended that the wildcard
 * resolution of resources coming from jars be thoroughly tested in your
 * specific environment before you rely on it.
 *
 * <p><b>{@code classpath*:} Prefix:</b>
 *
 * <p>There is special support for retrieving multiple class path resources with
 * the same name, via the "{@code classpath*:}" prefix. For example,
 * "{@code classpath*:META-INF/beans.xml}" will find all "beans.xml"
 * files in the class path, be it in "classes" directories or in JAR files.
 * This is particularly useful for autodetecting config files of the same name
 * at the same location within each jar file. Internally, this happens via a
 * {@code ClassLoader.getResources()} call, and is completely portable.
 *
 * <p>The "classpath*:" prefix can also be combined with a PathMatcher pattern in
 * the rest of the location path, for example "classpath*:META-INF/*-beans.xml".
 * In this case, the resolution strategy is fairly simple: a
 * {@code ClassLoader.getResources()} call is used on the last non-wildcard
 * path segment to get all the matching resources in the class loader hierarchy,
 * and then off each resource the same PathMatcher resolution strategy described
 * above is used for the wildcard subpath.
 *
 * <p><b>Other notes:</b>
 *
 * <p><b>WARNING:</b> Note that "{@code classpath*:}" when combined with
 * Ant-style patterns will only work reliably with at least one root directory
 * before the pattern starts, unless the actual target files reside in the file
 * system. This means that a pattern like "{@code classpath*:*.xml}" will
 * <i>not</i> retrieve files from the root of jar files but rather only from the
 * root of expanded directories. This originates from a limitation in the JDK's
 * {@code ClassLoader.getResources()} method which only returns file system
 * locations for a passed-in empty String (indicating potential roots to search).
 * This {@code ResourcePatternResolver} implementation is trying to mitigate the
 * jar root lookup limitation through {@link URLClassLoader} introspection and
 * "java.class.path" manifest evaluation; however, without portability guarantees.
 *
 * <p><b>WARNING:</b> Ant-style patterns with "classpath:" resources are not
 * guaranteed to find matching resources if the root package to search is available
 * in multiple class path locations. This is because a resource such as
 * <pre class="code">
 *     com/mycompany/package1/service-context.xml
 * </pre>
 * may be in only one location, but when a path such as
 * <pre class="code">
 *     classpath:com/mycompany/**&#47;service-context.xml
 * </pre>
 * is used to try to resolve it, the resolver will work off the (first) URL
 * returned by {@code getResource("com/mycompany");}. If this base package node
 * exists in multiple classloader locations, the actual end resource may not be
 * underneath. Therefore, preferably, use "{@code classpath*:}" with the same
 * Ant-style pattern in such a case, which will search <i>all</i> class path
 * locations that contain the root package.
 *
 * @author Juergen Hoeller
 * @author Colin Sampaleanu
 * @author Marius Bogoevici
 * @author Costin Leau
 * @author Phillip Webb
 * @since 1.0.2
 * @see #CLASSPATH_ALL_URL_PREFIX
 * @see org.springframework.util.AntPathMatcher
 * @see org.springframework.core.io.ResourceLoader#getResource(String)
 * @see ClassLoader#getResources(String)
 */
public class PathMatchingResourcePatternResolver implements ResourcePatternResolver {

	private static final Log logger = LogFactory.getLog(PathMatchingResourcePatternResolver.class);

	@Nullable
	private static Method equinoxResolveMethod;

	static {
		try {
			// Detect Equinox OSGi (e.g. on WebSphere 6.1)
			Class<?> fileLocatorClass = ClassUtils.forName("org.eclipse.core.runtime.FileLocator",
					PathMatchingResourcePatternResolver.class.getClassLoader());
			equinoxResolveMethod = fileLocatorClass.getMethod("resolve", URL.class);
			logger.trace("Found Equinox FileLocator for OSGi bundle URL resolution");
		}
		catch (Throwable ex) {
			equinoxResolveMethod = null;
		}
	}


	/**
	 * 内置的 ResourceLoader 资源定位器
	 */
	private final ResourceLoader resourceLoader;

	/**
	 * Ant 路径匹配器
	 */
	private PathMatcher pathMatcher = new AntPathMatcher();


	/**
	 * Create a new PathMatchingResourcePatternResolver with a DefaultResourceLoader.
	 * <p>ClassLoader access will happen via the thread context class loader.
	 * @see org.springframework.core.io.DefaultResourceLoader
	 */
	public PathMatchingResourcePatternResolver() {
		//创建一个默认的 ResourceLoader
		this.resourceLoader = new DefaultResourceLoader();
	}

	/**
	 * Create a new PathMatchingResourcePatternResolver.
	 * <p>ClassLoader access will happen via the thread context class loader.
	 * @param resourceLoader the ResourceLoader to load root directories and
	 * actual resources with
	 */
	public PathMatchingResourcePatternResolver(ResourceLoader resourceLoader) {
		Assert.notNull(resourceLoader, "ResourceLoader must not be null");
		this.resourceLoader = resourceLoader;
	}

	/**
	 * Create a new PathMatchingResourcePatternResolver with a DefaultResourceLoader.
	 * @param classLoader the ClassLoader to load classpath resources with,
	 * or {@code null} for using the thread context class loader
	 * at the time of actual resource access
	 * @see org.springframework.core.io.DefaultResourceLoader
	 */
	public PathMatchingResourcePatternResolver(@Nullable ClassLoader classLoader) {
		//用指定的 classLoader 创建 DefaultResourceLoader
		this.resourceLoader = new DefaultResourceLoader(classLoader);
	}


	/**
	 * Return the ResourceLoader that this pattern resolver works with.
	 */
	public ResourceLoader getResourceLoader() {
		return this.resourceLoader;
	}

	@Override
	@Nullable
	public ClassLoader getClassLoader() {
		return getResourceLoader().getClassLoader();
	}

	/**
	 * Set the PathMatcher implementation to use for this
	 * resource pattern resolver. Default is AntPathMatcher.
	 * @see org.springframework.util.AntPathMatcher
	 */
	public void setPathMatcher(PathMatcher pathMatcher) {
		Assert.notNull(pathMatcher, "PathMatcher must not be null");
		this.pathMatcher = pathMatcher;
	}

	/**
	 * Return the PathMatcher that this resource pattern resolver uses.
	 */
	public PathMatcher getPathMatcher() {
		return this.pathMatcher;
	}


	@Override
	public Resource getResource(String location) {
		//调用 ResourceLoader 来获取资源
		return getResourceLoader().getResource(location);
	}

	/**
	 * 根据 pattern 获取资源列表, 这里有四种情况
	 * - 有 classpath*: 开头的
	 * 		- 有通配符 , 先获取没有通配符的根目录, 然后获取这个目录下的所有文件(也就下面没有通配符的查询), 再做通配符过滤
	 * 	    - 没有通配符, 通过 ClassLoader.getResources() 获取指文件, 有多个则返回多个
	 *
	 * - 没有 classpath*: 开头的
	 * 		- 有通配符, 先获取没有通配符的根目录, 然后获取这个目录下的所有文件(也就下面没有通配符的查询), 再做通配符过滤
	 * 	    - 没有通配符, 则通过指定的 ResourceLoader.getResource() 来获取
	 *
	 */
	@Override
	public Resource[] getResources(String locationPattern) throws IOException {
		//locationPattern 非 null
		Assert.notNull(locationPattern, "Location pattern must not be null");
		//如果以 "classpath*:" 开头
		if (locationPattern.startsWith(CLASSPATH_ALL_URL_PREFIX)) {
			//如果是 locationPattern 是 pattern 匹配模式
			// a class path resource (multiple resources for same name possible)
			if (getPathMatcher().isPattern(locationPattern.substring(CLASSPATH_ALL_URL_PREFIX.length()))) {
				//查出通配符符合的所有资源
				// a class path resource pattern
				return findPathMatchingResources(locationPattern);
			}
			else {
				//获取指定路径下面的资源, 因为同一个文件资源可能有多个
				// all class path resources with the given name
				return findAllClassPathResources(locationPattern.substring(CLASSPATH_ALL_URL_PREFIX.length()));
			}
		}
		//非 classpath*: 开头的
		else {
			//获取前缀
			// Generally only look for a pattern after a prefix here,
			// and on Tomcat only after the "*/" separator for its "war:" protocol.
			int prefixEnd = (locationPattern.startsWith("war:") ? locationPattern.indexOf("*/") + 1 :
					locationPattern.indexOf(':') + 1);
			//如果前缀后面有模式匹配
			if (getPathMatcher().isPattern(locationPattern.substring(prefixEnd))) {
				//则获取匹配的资源
				// a file pattern
				return findPathMatchingResources(locationPattern);
			}
			else {
				//通过 ResourceLoader 加载单个资源返回
				// a single resource with the given name
				return new Resource[] {getResourceLoader().getResource(locationPattern)};
			}
		}
	}

	/**
	 * 返回 classes 路径下和所有 jar 包中的所有相匹配的资源
	 * Find all class location resources with the given location via the ClassLoader.
	 * Delegates to {@link #doFindAllClassPathResources(String)}.
	 * @param location the absolute path within the classpath
	 * @return the result as Resource array
	 * @throws IOException in case of I/O errors
	 * @see java.lang.ClassLoader#getResources
	 * @see #convertClassLoaderURL
	 */
	protected Resource[] findAllClassPathResources(String location) throws IOException {
		String path = location;
		//去掉首个 /
		if (path.startsWith("/")) {
			path = path.substring(1);
		}
		//获取 classPath 的 jar 资源
		Set<Resource> result = doFindAllClassPathResources(path);
		if (logger.isTraceEnabled()) {
			logger.trace("Resolved classpath location [" + location + "] to resources " + result);
		}
		//变结果变成数组
		return result.toArray(new Resource[0]);
	}

	/**
	 * 注意:
	 * - ClassLoader.getResources()  是返回当前类加载器路径上的所有重复资源以及父类加载器上的所有重复资源
	 * - ClassLoader.getResource()  是返回类路径上碰到的第一个资源
	 * Find all class location resources with the given path via the ClassLoader.
	 * Called by {@link #findAllClassPathResources(String)}.
	 * @param path the absolute path within the classpath (never a leading slash)
	 * @return a mutable Set of matching Resource instances
	 * @since 4.1.1
	 */
	protected Set<Resource> doFindAllClassPathResources(String path) throws IOException {
		//保存 Resource 结果
		Set<Resource> result = new LinkedHashSet<>(16);
		//获取 classLoader
		ClassLoader cl = getClassLoader();
		//获取 path 下的资源
		Enumeration<URL> resourceUrls = (cl != null ? cl.getResources(path) : ClassLoader.getSystemResources(path));
		//如果有更多的值
		while (resourceUrls.hasMoreElements()) {
			//获取资源
			URL url = resourceUrls.nextElement();
			//将 url 转成 Resource
			result.add(convertClassLoaderURL(url));
		}
		//如果 path 是空串
		if ("".equals(path)) {
			// The above result is likely to be incomplete, i.e. only containing file system references.
			// We need to have pointers to each of the jar files on the classpath as well...
			addAllClassLoaderJarRoots(cl, result);
		}
		return result;
	}

	/**
	 * 将 URL 转成 UrlResource
	 * Convert the given URL as returned from the ClassLoader into a {@link Resource}.
	 * <p>The default implementation simply creates a {@link UrlResource} instance.
	 * @param url a URL as returned from the ClassLoader
	 * @return the corresponding Resource object
	 * @see java.lang.ClassLoader#getResources
	 * @see org.springframework.core.io.Resource
	 */
	protected Resource convertClassLoaderURL(URL url) {
		return new UrlResource(url);
	}

	/**
	 * Search all {@link URLClassLoader} URLs for jar file references and add them to the
	 * given set of resources in the form of pointers to the root of the jar file content.
	 * @param classLoader the ClassLoader to search (including its ancestors)
	 * @param result the set of resources to add jar roots to
	 * @since 4.1.1
	 */
	protected void addAllClassLoaderJarRoots(@Nullable ClassLoader classLoader, Set<Resource> result) {
		//如果 classLoader 是 URLClassLoader
		if (classLoader instanceof URLClassLoader) {
			try {
				//遍历所有的 url, 这里获取的 url 有可能是带有 /
				for (URL url : ((URLClassLoader) classLoader).getURLs()) {
					try {
						//如果 url 是 jar 包的资源, 则创建 UrlResource, 否则创建拼接 jar 协议头
						UrlResource jarResource = (ResourceUtils.URL_PROTOCOL_JAR.equals(url.getProtocol()) ?
								new UrlResource(url) :
								new UrlResource(ResourceUtils.JAR_URL_PREFIX + url + ResourceUtils.JAR_URL_SEPARATOR));
						//如果资源存在
						if (jarResource.exists()) {
							//加入到结果中
							result.add(jarResource);
						}
					}
					catch (MalformedURLException ex) {
						if (logger.isDebugEnabled()) {
							logger.debug("Cannot search for matching files underneath [" + url +
									"] because it cannot be converted to a valid 'jar:' URL: " + ex.getMessage());
						}
					}
				}
			}
			catch (Exception ex) {
				if (logger.isDebugEnabled()) {
					logger.debug("Cannot introspect jar files since ClassLoader [" + classLoader +
							"] does not support 'getURLs()': " + ex);
				}
			}
		}

		//如果是 SystemClassLoader
		if (classLoader == ClassLoader.getSystemClassLoader()) {
			// "java.class.path" manifest evaluation...
			addClassPathManifestEntries(result);
		}

		if (classLoader != null) {
			try {
				//获取父加载器的资源
				// Hierarchy traversal...
				addAllClassLoaderJarRoots(classLoader.getParent(), result);
			}
			catch (Exception ex) {
				if (logger.isDebugEnabled()) {
					logger.debug("Cannot introspect jar files in parent ClassLoader since [" + classLoader +
							"] does not support 'getParent()': " + ex);
				}
			}
		}
	}

	/**
	 * 从指定的 classPath 中获取所有的 jar 资源
	 * 注意: 只有 jar:file:/a/b.jar!/ 这种路径才能创建 URL 对象
	 * Determine jar file references from the "java.class.path." manifest property and add them
	 * to the given set of resources in the form of pointers to the root of the jar file content.
	 * @param result the set of resources to add jar roots to
	 * @since 4.3
	 */
	protected void addClassPathManifestEntries(Set<Resource> result) {
		try {
			//获取指定的 classPath
			String javaClassPathProperty = System.getProperty("java.class.path");
			//用配置的
			for (String path : StringUtils.delimitedListToStringArray(
					javaClassPathProperty, System.getProperty("path.separator"))) {
				try {
					//获取文件的绝对路径
					String filePath = new File(path).getAbsolutePath();
					//获取 : 的索引位置
					int prefixIndex = filePath.indexOf(':');
					//如果有 : 且索引为 1, 则有可能是 window 的盘符
					if (prefixIndex == 1) {
						//将第一个字母大写
						// Possibly "c:" drive prefix on Windows, to be upper-cased for proper duplicate detection
						filePath = StringUtils.capitalize(filePath);
					}
					//拼接成 jar 协议的路径, 并且创建 UrlResource 资源,
					UrlResource jarResource = new UrlResource(ResourceUtils.JAR_URL_PREFIX +
							ResourceUtils.FILE_URL_PREFIX + filePath + ResourceUtils.JAR_URL_SEPARATOR);
					//如果 result 没有包括这个资源, 且 filePath 和 result 中的没有重复, 且 资源存在
					// Potentially overlapping with URLClassLoader.getURLs() result above!
					if (!result.contains(jarResource) && !hasDuplicate(filePath, result) && jarResource.exists()) {
						//加入到结果当中
						result.add(jarResource);
					}
				}
				catch (MalformedURLException ex) {
					if (logger.isDebugEnabled()) {
						logger.debug("Cannot search for matching files underneath [" + path +
								"] because it cannot be converted to a valid 'jar:' URL: " + ex.getMessage());
					}
				}
			}
		}
		catch (Exception ex) {
			if (logger.isDebugEnabled()) {
				logger.debug("Failed to evaluate 'java.class.path' manifest entries: " + ex);
			}
		}
	}

	/**
	 * Check whether the given file path has a duplicate but differently structured entry
	 * in the existing result, i.e. with or without a leading slash.
	 * @param filePath the file path (with or without a leading slash)
	 * @param result the current result
	 * @return {@code true} if there is a duplicate (i.e. to ignore the given file path),
	 * {@code false} to proceed with adding a corresponding resource to the current result
	 */
	private boolean hasDuplicate(String filePath, Set<Resource> result) {
		//如果 result 为空, 则返回 false
		if (result.isEmpty()) {
			return false;
		}
		//获取可能重复的 path
		//todo wolfleong 为什么要这么处理, 不太懂
		String duplicatePath = (filePath.startsWith("/") ? filePath.substring(1) : "/" + filePath);
		try {
			//如果结果包括对应的资源, 则重复
			return result.contains(new UrlResource(ResourceUtils.JAR_URL_PREFIX + ResourceUtils.FILE_URL_PREFIX +
					duplicatePath + ResourceUtils.JAR_URL_SEPARATOR));
		}
		catch (MalformedURLException ex) {
			//报异常则表示不重复
			// Ignore: just for testing against duplicate.
			return false;
		}
	}

	/**
	 * 具体的思路是, 将 locationPattern 分成两部份, 不带通配符的根路径和带通配符的路径,
	 * 然后先通过 getResources() 获取不带通配符的所有资源, 再通过带通配符的路径去匹配
	 *
	 * Find all resources that match the given location pattern via the
	 * Ant-style PathMatcher. Supports resources in jar files and zip files
	 * and in the file system.
	 * @param locationPattern the location pattern to match
	 * @return the result as Resource array
	 * @throws IOException in case of I/O errors
	 * @see #doFindPathMatchingJarResources
	 * @see #doFindPathMatchingFileResources
	 * @see org.springframework.util.PathMatcher
	 */
	protected Resource[] findPathMatchingResources(String locationPattern) throws IOException {
		//获取根目录路径, 不带通配符的路径
		String rootDirPath = determineRootDir(locationPattern);
		//获取子路径, 带有 pattern 的路径
		String subPattern = locationPattern.substring(rootDirPath.length());
		//获取不带通配符根目录下的所有资源
		Resource[] rootDirResources = getResources(rootDirPath);
		Set<Resource> result = new LinkedHashSet<>(16);
		//遍历资源
		for (Resource rootDirResource : rootDirResources) {
			//不知道做什么处理, resolveRootDirResource 方法没有逻辑实现
			rootDirResource = resolveRootDirResource(rootDirResource);
			//获取资源的 URL
			URL rootDirUrl = rootDirResource.getURL();
			//如果 equinoxResolveMethod 不为 null, 且 rootDirUrl 协议是 bundle
			if (equinoxResolveMethod != null && rootDirUrl.getProtocol().startsWith("bundle")) {
				URL resolvedUrl = (URL) ReflectionUtils.invokeMethod(equinoxResolveMethod, null, rootDirUrl);
				if (resolvedUrl != null) {
					rootDirUrl = resolvedUrl;
				}
				rootDirResource = new UrlResource(rootDirUrl);
			}
			//如果是 vfs , 没弄过不清楚
			if (rootDirUrl.getProtocol().startsWith(ResourceUtils.URL_PROTOCOL_VFS)) {
				result.addAll(VfsResourceMatchingDelegate.findMatchingResources(rootDirUrl, subPattern, getPathMatcher()));
			}
			//如果是 jarUrl 或者是 Jar 资源
			else if (ResourceUtils.isJarURL(rootDirUrl) || isJarResource(rootDirResource)) {
				//获取匹配的 jar 资源添加到 result
				result.addAll(doFindPathMatchingJarResources(rootDirResource, rootDirUrl, subPattern));
			}
			else {
				//处理文件资源
				result.addAll(doFindPathMatchingFileResources(rootDirResource, subPattern));
			}
		}
		if (logger.isTraceEnabled()) {
			logger.trace("Resolved location pattern [" + locationPattern + "] to resources " + result);
		}
		//列表变数组
		return result.toArray(new Resource[0]);
	}

	/**
	 * 获取 pattern 前面的根目录, 也就不带通配符的上一层目录
	 * 注意: String.lastIndexOf(String str,int fromIndex) 中的 fromIndex 为开始反向搜索的索引位置
	 * Determine the root directory for the given location.
	 * <p>Used for determining the starting point for file matching,
	 * resolving the root directory location to a {@code java.io.File}
	 * and passing it into {@code retrieveMatchingFiles}, with the
	 * remainder of the location as pattern.
	 * <p>Will return "/WEB-INF/" for the pattern "/WEB-INF/*.xml",
	 * for example.
	 * @param location the location to check
	 * @return the part of the location that denotes the root directory
	 * @see #retrieveMatchingFiles
	 */
	protected String determineRootDir(String location) {
		//获取 : 的后一位
		int prefixEnd = location.indexOf(':') + 1;
		//根目录结束位置
		int rootDirEnd = location.length();
		//相当于从后面往回迭代, 直到找到一个目录不是 pattern 的为止,
		//todo wolfleong 不知道为什么是 rootDirEnd - 2 , 而不是 rootDirEnd - 1
		while (rootDirEnd > prefixEnd && getPathMatcher().isPattern(location.substring(prefixEnd, rootDirEnd))) {
			//这里为什么要 + 1, 主要是后面 substring 时, end 的索引是不包括的
			rootDirEnd = location.lastIndexOf('/', rootDirEnd - 2) + 1;
		}
		//如果 rootDirEnd 为 0 , 则表示带通配符可能在第一个目录
		if (rootDirEnd == 0) {
			//设置初始的位置为根目录结束位置
			rootDirEnd = prefixEnd;
		}
		//获取根目录, 返回的目录是有带前缀的
		return location.substring(0, rootDirEnd);
	}

	/**
	 * Resolve the specified resource for path matching.
	 * <p>By default, Equinox OSGi "bundleresource:" / "bundleentry:" URL will be
	 * resolved into a standard jar file URL that be traversed using Spring's
	 * standard jar file traversal algorithm. For any preceding custom resolution,
	 * override this method and replace the resource handle accordingly.
	 * @param original the resource to resolve
	 * @return the resolved resource (may be identical to the passed-in resource)
	 * @throws IOException in case of resolution failure
	 */
	protected Resource resolveRootDirResource(Resource original) throws IOException {
		return original;
	}

	/**
	 * Return whether the given resource handle indicates a jar resource
	 * that the {@code doFindPathMatchingJarResources} method can handle.
	 * <p>By default, the URL protocols "jar", "zip", "vfszip and "wsjar"
	 * will be treated as jar resources. This template method allows for
	 * detecting further kinds of jar-like resources, e.g. through
	 * {@code instanceof} checks on the resource handle type.
	 * @param resource the resource handle to check
	 * (usually the root directory to start path matching from)
	 * @see #doFindPathMatchingJarResources
	 * @see org.springframework.util.ResourceUtils#isJarURL
	 */
	protected boolean isJarResource(Resource resource) throws IOException {
		return false;
	}

	/**
	 * 从 Jar 包资源中匹配
	 * Find all resources in jar files that match the given location pattern
	 * via the Ant-style PathMatcher.
	 * @param rootDirResource the root directory as Resource
	 * @param rootDirURL the pre-resolved root directory URL
	 * @param subPattern the sub pattern to match (below the root directory)
	 * @return a mutable Set of matching Resource instances
	 * @throws IOException in case of I/O errors
	 * @since 4.3
	 * @see java.net.JarURLConnection
	 * @see org.springframework.util.PathMatcher
	 */
	protected Set<Resource> doFindPathMatchingJarResources(Resource rootDirResource, URL rootDirURL, String subPattern)
			throws IOException {

		//打开连接, rootDirURL=jar:file:/com/wl/test.jar!/org/springframework/
		URLConnection con = rootDirURL.openConnection();
		//JarFile 对象
		JarFile jarFile;
		// jarFileUrl = file:/com/wl/test.jar
		String jarFileUrl;
		// rootEntryPath=/org/springframework/
		String rootEntryPath;
		//是不是关闭
		boolean closeJarFile;

		//如果连接是 JarURLConnection
		if (con instanceof JarURLConnection) {
			//强转
			// Should usually be the case for traditional JAR files.
			JarURLConnection jarCon = (JarURLConnection) con;
			//设置连接缓存
			ResourceUtils.useCachesIfNecessary(jarCon);
			//获取 JarFile
			jarFile = jarCon.getJarFile();
			//获取 JarFileUrl
			jarFileUrl = jarCon.getJarFileURL().toExternalForm();
			//获取 JarEntry
			JarEntry jarEntry = jarCon.getJarEntry();
			// 如果 JarEntry 不为 null , 则获取 jarEntry 的名称否则返回空串
			rootEntryPath = (jarEntry != null ? jarEntry.getName() : "");
			//如果没使用缓存, 则表示最后要关闭连接
			closeJarFile = !jarCon.getUseCaches();
		}
		//如果不是 JarUrlConnection, 是其他 Connection
		else {
			//获取文件路径, 如: file:/com/wl/test.jar!/org/springframework/
			// No JarURLConnection -> need to resort to URL file parsing.
			// We'll assume URLs of the format "jar:path!/entry", with the protocol
			// being arbitrary as long as following the entry format.
			// We'll also handle paths with and without leading "file:" prefix.
			String urlFile = rootDirURL.getFile();
			try {
				//如果是带 */ , 则证明是 war 包的协议
				int separatorIndex = urlFile.indexOf(ResourceUtils.WAR_URL_SEPARATOR);
				//如果没有找到
				if (separatorIndex == -1) {
					//则获取 !/ 的索引
					separatorIndex = urlFile.indexOf(ResourceUtils.JAR_URL_SEPARATOR);
				}
				//如果有找到分割符的位置
				if (separatorIndex != -1) {
					//截掉分割符后面的, 如: file:/com/wl/test.jar
					jarFileUrl = urlFile.substring(0, separatorIndex);
					//获取 jar 包下面的路径, 如: /org/springframework/
					rootEntryPath = urlFile.substring(separatorIndex + 2);  // both separators are 2 chars
					//获取 jarFile 文件
					jarFile = getJarFile(jarFileUrl);
				}
				else {
					//如果没有分割符, 则直接创建
					jarFile = new JarFile(urlFile);
					//赋值,
					jarFileUrl = urlFile;
					rootEntryPath = "";
				}
				//不是缓存, 都是新创建的, 可以关闭
				closeJarFile = true;
			}
			catch (ZipException ex) {
				if (logger.isDebugEnabled()) {
					logger.debug("Skipping invalid jar classpath entry [" + urlFile + "]");
				}
				return Collections.emptySet();
			}
		}

		try {
			if (logger.isTraceEnabled()) {
				logger.trace("Looking for matching resources in jar file [" + jarFileUrl + "]");
			}
			//如: rootEntryPath = /org/springframework/ , jarFileUrl=file:/a/b/abc.jar
			//如果 rootEntryPath 非空串且非 / 结束
			if (!"".equals(rootEntryPath) && !rootEntryPath.endsWith("/")) {
				//拼接 / 在后面
				// Root entry path must end with slash to allow for proper matching.
				// The Sun JRE does not return a slash here, but BEA JRockit does.
				rootEntryPath = rootEntryPath + "/";
			}
			Set<Resource> result = new LinkedHashSet<>(8);
			//遍历 jar 包里的对象
			for (Enumeration<JarEntry> entries = jarFile.entries(); entries.hasMoreElements();) {
				JarEntry entry = entries.nextElement();
				//如: /org/springframework/core
				String entryPath = entry.getName();
				//如果文件名称是以 rootEntryPath 开始的
				if (entryPath.startsWith(rootEntryPath)) {
					//获取相对于 rootEntryPath 的路径
					String relativePath = entryPath.substring(rootEntryPath.length());
					//然后用匹配器进行 匹配
					if (getPathMatcher().match(subPattern, relativePath)) {
						//匹配成功, 就创建 rootDirResource 的相对资源
						result.add(rootDirResource.createRelative(relativePath));
					}
				}
			}
			//返回结果
			return result;
		}
		finally {
			//如果要关闭 jarFile
			if (closeJarFile) {
				//关闭
				jarFile.close();
			}
		}
	}

	/**
	 * Resolve the given jar file URL into a JarFile object.
	 */
	protected JarFile getJarFile(String jarFileUrl) throws IOException {
		//如果 url 是普通文件协议(file:)开头
		if (jarFileUrl.startsWith(ResourceUtils.FILE_URL_PREFIX)) {
			try {
				//获取除了协议后面的内容
				return new JarFile(ResourceUtils.toURI(jarFileUrl).getSchemeSpecificPart());
			}
			catch (URISyntaxException ex) {
				//如果报异常, 则获取协议文件后面的内容
				// Fallback for URLs that are not valid URIs (should hardly ever happen).
				return new JarFile(jarFileUrl.substring(ResourceUtils.FILE_URL_PREFIX.length()));
			}
		}
		else {
			//如果不是文件协议开头的, 则直接创建
			return new JarFile(jarFileUrl);
		}
	}

	/**
	 * 从文件统系资源中匹配文件.
	 * - 这里做的逻辑是, 获取文件的绝对路径文件, 然后交给 doFindMatchingFileSystemResources() 处理
	 * Find all resources in the file system that match the given location pattern
	 * via the Ant-style PathMatcher.
	 * @param rootDirResource the root directory as Resource
	 * @param subPattern the sub pattern to match (below the root directory)
	 * @return a mutable Set of matching Resource instances
	 * @throws IOException in case of I/O errors
	 * @see #retrieveMatchingFiles
	 * @see org.springframework.util.PathMatcher
	 */
	protected Set<Resource> doFindPathMatchingFileResources(Resource rootDirResource, String subPattern)
			throws IOException {

		File rootDir;
		try {
			//获取绝对路径的文件
			rootDir = rootDirResource.getFile().getAbsoluteFile();
		}
		catch (FileNotFoundException ex) {
			if (logger.isDebugEnabled()) {
				logger.debug("Cannot search for matching files underneath " + rootDirResource +
						" in the file system: " + ex.getMessage());
			}
			//如果异常则返回空列表
			return Collections.emptySet();
		}
		catch (Exception ex) {
			if (logger.isInfoEnabled()) {
				logger.info("Failed to resolve " + rootDirResource + " in the file system: " + ex);
			}
			return Collections.emptySet();
		}
		//从系统文件中获取
		return doFindMatchingFileSystemResources(rootDir, subPattern);
	}

	/**
	 * Find all resources in the file system that match the given location pattern
	 * via the Ant-style PathMatcher.
	 * @param rootDir the root directory in the file system
	 * @param subPattern the sub pattern to match (below the root directory)
	 * @return a mutable Set of matching Resource instances
	 * @throws IOException in case of I/O errors
	 * @see #retrieveMatchingFiles
	 * @see org.springframework.util.PathMatcher
	 */
	protected Set<Resource> doFindMatchingFileSystemResources(File rootDir, String subPattern) throws IOException {
		if (logger.isTraceEnabled()) {
			logger.trace("Looking for matching resources in directory tree [" + rootDir.getPath() + "]");
		}
		Set<File> matchingFiles = retrieveMatchingFiles(rootDir, subPattern);
		Set<Resource> result = new LinkedHashSet<>(matchingFiles.size());
		for (File file : matchingFiles) {
			result.add(new FileSystemResource(file));
		}
		return result;
	}

	/**
	 * 检索匹配的文件.
	 * - 这里主要是对 rootDir 文件进行一些校验, 并且拼接 pattern , 然后交给 doRetrieveMatchingFiles() 处理
	 * Retrieve files that match the given path pattern,
	 * checking the given directory and its subdirectories.
	 * @param rootDir the directory to start from
	 * @param pattern the pattern to match against,
	 * relative to the root directory
	 * @return a mutable Set of matching Resource instances
	 * @throws IOException if directory contents could not be retrieved
	 */
	protected Set<File> retrieveMatchingFiles(File rootDir, String pattern) throws IOException {
		//如果 rootDir 文件不存在, 则返回空列表
		if (!rootDir.exists()) {
			// Silently skip non-existing directories.
			if (logger.isDebugEnabled()) {
				logger.debug("Skipping [" + rootDir.getAbsolutePath() + "] because it does not exist");
			}
			return Collections.emptySet();
		}
		//如果 rootDir 非目录, 则返回空列表
		if (!rootDir.isDirectory()) {
			// Complain louder if it exists but is no directory.
			if (logger.isInfoEnabled()) {
				logger.info("Skipping [" + rootDir.getAbsolutePath() + "] because it does not denote a directory");
			}
			return Collections.emptySet();
		}
		//如果 rootDir 不能读, 则返回空列表
		if (!rootDir.canRead()) {
			if (logger.isInfoEnabled()) {
				logger.info("Skipping search for matching files underneath directory [" + rootDir.getAbsolutePath() +
						"] because the application is not allowed to read the directory");
			}
			return Collections.emptySet();
		}
		//替换文件路径
		String fullPattern = StringUtils.replace(rootDir.getAbsolutePath(), File.separator, "/");
		//如果 pattern 不以 / 开头, 则 fullPattern 后面要拼接 /
		if (!pattern.startsWith("/")) {
			fullPattern += "/";
		}
		//合并
		fullPattern = fullPattern + StringUtils.replace(pattern, File.separator, "/");
		Set<File> result = new LinkedHashSet<>(8);
		//检索
		doRetrieveMatchingFiles(fullPattern, rootDir, result);
		return result;
	}

	/**
	 * 匹配指定目录下的文件
	 * Recursively retrieve files that match the given pattern,
	 * adding them to the given result list.
	 * @param fullPattern the pattern to match against,
	 * with prepended root directory path
	 * @param dir the current directory
	 * @param result the Set of matching File instances to add to
	 * @throws IOException if directory contents could not be retrieved
	 */
	protected void doRetrieveMatchingFiles(String fullPattern, File dir, Set<File> result) throws IOException {
		if (logger.isTraceEnabled()) {
			logger.trace("Searching directory [" + dir.getAbsolutePath() +
					"] for files matching pattern [" + fullPattern + "]");
		}
		//遍历目录下的文件
		for (File content : listDirectory(dir)) {
			//替换文件分割符
			String currPath = StringUtils.replace(content.getAbsolutePath(), File.separator, "/");
			//如果文件是目录, 且前面的可以匹配
			if (content.isDirectory() && getPathMatcher().matchStart(fullPattern, currPath + "/")) {
				//如果不可读, 则打日志
				if (!content.canRead()) {
					if (logger.isDebugEnabled()) {
						logger.debug("Skipping subdirectory [" + dir.getAbsolutePath() +
								"] because the application is not allowed to read the directory");
					}
				}
				else {
					//当前目录下的文件
					doRetrieveMatchingFiles(fullPattern, content, result);
				}
			}
			//如果不是目录, 则为文件, 然后用 pattern 去匹配
			if (getPathMatcher().match(fullPattern, currPath)) {
				//如果匹配上则添加
				result.add(content);
			}
		}
	}

	/**
	 * 获取 File 目录下的文件
	 * Determine a sorted list of files in the given directory.
	 * @param dir the directory to introspect
	 * @return the sorted list of files (by default in alphabetical order)
	 * @since 5.1
	 * @see File#listFiles()
	 */
	protected File[] listDirectory(File dir) {
		//获取文件列表
		File[] files = dir.listFiles();
		//如果目录下没有文件
		if (files == null) {
			if (logger.isInfoEnabled()) {
				logger.info("Could not retrieve contents of directory [" + dir.getAbsolutePath() + "]");
			}
			//返回空数组
			return new File[0];
		}
		//按文件名排序
		Arrays.sort(files, Comparator.comparing(File::getName));
		return files;
	}


	/**
	 * Inner delegate class, avoiding a hard JBoss VFS API dependency at runtime.
	 */
	private static class VfsResourceMatchingDelegate {

		public static Set<Resource> findMatchingResources(
				URL rootDirURL, String locationPattern, PathMatcher pathMatcher) throws IOException {

			Object root = VfsPatternUtils.findRoot(rootDirURL);
			PatternVirtualFileVisitor visitor =
					new PatternVirtualFileVisitor(VfsPatternUtils.getPath(root), locationPattern, pathMatcher);
			VfsPatternUtils.visit(root, visitor);
			return visitor.getResources();
		}
	}


	/**
	 * VFS visitor for path matching purposes.
	 */
	@SuppressWarnings("unused")
	private static class PatternVirtualFileVisitor implements InvocationHandler {

		private final String subPattern;

		private final PathMatcher pathMatcher;

		private final String rootPath;

		private final Set<Resource> resources = new LinkedHashSet<>();

		public PatternVirtualFileVisitor(String rootPath, String subPattern, PathMatcher pathMatcher) {
			this.subPattern = subPattern;
			this.pathMatcher = pathMatcher;
			this.rootPath = (rootPath.isEmpty() || rootPath.endsWith("/") ? rootPath : rootPath + "/");
		}

		@Override
		@Nullable
		public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
			String methodName = method.getName();
			if (Object.class == method.getDeclaringClass()) {
				if (methodName.equals("equals")) {
					// Only consider equal when proxies are identical.
					return (proxy == args[0]);
				}
				else if (methodName.equals("hashCode")) {
					return System.identityHashCode(proxy);
				}
			}
			else if ("getAttributes".equals(methodName)) {
				return getAttributes();
			}
			else if ("visit".equals(methodName)) {
				visit(args[0]);
				return null;
			}
			else if ("toString".equals(methodName)) {
				return toString();
			}

			throw new IllegalStateException("Unexpected method invocation: " + method);
		}

		public void visit(Object vfsResource) {
			if (this.pathMatcher.match(this.subPattern,
					VfsPatternUtils.getPath(vfsResource).substring(this.rootPath.length()))) {
				this.resources.add(new VfsResource(vfsResource));
			}
		}

		@Nullable
		public Object getAttributes() {
			return VfsPatternUtils.getVisitorAttributes();
		}

		public Set<Resource> getResources() {
			return this.resources;
		}

		public int size() {
			return this.resources.size();
		}

		@Override
		public String toString() {
			return "sub-pattern: " + this.subPattern + ", resources: " + this.resources;
		}
	}

}
