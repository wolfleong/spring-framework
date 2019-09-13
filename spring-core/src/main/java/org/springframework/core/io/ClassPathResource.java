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

package org.springframework.core.io;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;

import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;

/**
 * 加载类路径下的资源, 主要是两种 api 的类资源加载, 分为有指定 class 和指定 classLoader 来加载
 * - class.getResource(path) 其中的 path 有两种形式, path不以’/'开头时，默认是从此类所在的包下取资源, path  以’/'开头时，则是从ClassPath根下获取
 * - class.getClassLoader().getResource(), path 不以 '/' 开头跟上面 path 以 '/' 开头的处理样, 从 classPath 根下获取, 以 path 开头返回 null
 *
 * {@link Resource} implementation for class path resources. Uses either a
 * given {@link ClassLoader} or a given {@link Class} for loading resources.
 *
 * <p>Supports resolution as {@code java.io.File} if the class path
 * resource resides in the file system, but not for resources in a JAR.
 * Always supports resolution as URL.
 *
 * @author Juergen Hoeller
 * @author Sam Brannen
 * @since 28.12.2003
 * @see ClassLoader#getResourceAsStream(String)
 * @see Class#getResourceAsStream(String)
 */
public class ClassPathResource extends AbstractFileResolvingResource {

	/**
	 * 资源路径
	 */
	private final String path;

	/**
	 * 类加载器
	 */
	@Nullable
	private ClassLoader classLoader;

	/**
	 * 类
	 */
	@Nullable
	private Class<?> clazz;


	/**
	 * 根据指定资源路径创建 ClassPathResource
	 * Create a new {@code ClassPathResource} for {@code ClassLoader} usage.
	 * A leading slash will be removed, as the ClassLoader resource access
	 * methods will not accept it.
	 * <p>The thread context class loader will be used for
	 * loading the resource.
	 * @param path the absolute path within the class path
	 * @see java.lang.ClassLoader#getResourceAsStream(String)
	 * @see org.springframework.util.ClassUtils#getDefaultClassLoader()
	 */
	public ClassPathResource(String path) {
		this(path, (ClassLoader) null);
	}

	/**
	 * 根据资源路径和类加载器创建
	 * Create a new {@code ClassPathResource} for {@code ClassLoader} usage.
	 * A leading slash will be removed, as the ClassLoader resource access
	 * methods will not accept it.
	 * @param path the absolute path within the classpath
	 * @param classLoader the class loader to load the resource with,
	 * or {@code null} for the thread context class loader
	 * @see ClassLoader#getResourceAsStream(String)
	 */
	public ClassPathResource(String path, @Nullable ClassLoader classLoader) {
		Assert.notNull(path, "Path must not be null");
		//处理一下 path
		String pathToUse = StringUtils.cleanPath(path);
		//如果是以 / 为前缀
		if (pathToUse.startsWith("/")) {
			//截掉 / , 为什么呢, 主要是因为 classLoader.getResource() 不支持以 / 开头的路径
			pathToUse = pathToUse.substring(1);
		}
		this.path = pathToUse;
		//如果加载器为 null, 则获取默认的类加载器
		this.classLoader = (classLoader != null ? classLoader : ClassUtils.getDefaultClassLoader());
	}

	/**
	 * Create a new {@code ClassPathResource} for {@code Class} usage.
	 * The path can be relative to the given class, or absolute within
	 * the classpath via a leading slash.
	 * @param path relative or absolute path within the class path
	 * @param clazz the class to load resources with
	 * @see java.lang.Class#getResourceAsStream
	 */
	public ClassPathResource(String path, @Nullable Class<?> clazz) {
		Assert.notNull(path, "Path must not be null");
		this.path = StringUtils.cleanPath(path);
		this.clazz = clazz;
	}

	/**
	 * Create a new {@code ClassPathResource} with optional {@code ClassLoader}
	 * and {@code Class}. Only for internal usage.
	 * @param path relative or absolute path within the classpath
	 * @param classLoader the class loader to load the resource with, if any
	 * @param clazz the class to load resources with, if any
	 * @deprecated as of 4.3.13, in favor of selective use of
	 * {@link #ClassPathResource(String, ClassLoader)} vs {@link #ClassPathResource(String, Class)}
	 */
	@Deprecated
	protected ClassPathResource(String path, @Nullable ClassLoader classLoader, @Nullable Class<?> clazz) {
		this.path = StringUtils.cleanPath(path);
		this.classLoader = classLoader;
		this.clazz = clazz;
	}


	/**
	 * Return the path for this resource (as resource path within the class path).
	 */
	public final String getPath() {
		return this.path;
	}

	/**
	 * Return the ClassLoader that this resource will be obtained from.
	 */
	@Nullable
	public final ClassLoader getClassLoader() {
		//如果 clazz 不为 null, 则获取类的加载器, 否则返回指定的 classLoader
		return (this.clazz != null ? this.clazz.getClassLoader() : this.classLoader);
	}


	/**
	 * This implementation checks for the resolution of a resource URL.
	 * @see java.lang.ClassLoader#getResource(String)
	 * @see java.lang.Class#getResource(String)
	 */
	@Override
	public boolean exists() {
		return (resolveURL() != null);
	}

	/**
	 * Resolves a URL for the underlying class path resource.
	 * @return the resolved URL, or {@code null} if not resolvable
	 */
	@Nullable
	protected URL resolveURL() {
		//如查类不为null
		if (this.clazz != null) {
			//用这个类
			return this.clazz.getResource(this.path);
		}
		//如果 classLoader 不为 null
		else if (this.classLoader != null) {
			//用 classLoader 来获取 path 的资源
			return this.classLoader.getResource(this.path);
		}
		else {
			//如果没有指定的 classLoader , 则用系统的ClassLoader 来获取 path 的资源
			return ClassLoader.getSystemResource(this.path);
		}
	}

	/**
	 * This implementation opens an InputStream for the given class path resource.
	 * @see java.lang.ClassLoader#getResourceAsStream(String)
	 * @see java.lang.Class#getResourceAsStream(String)
	 */
	@Override
	public InputStream getInputStream() throws IOException {
		InputStream is;
		//如果 class 不为 null
		if (this.clazz != null) {
			//通过 class 获取 path 的 inputStream
			is = this.clazz.getResourceAsStream(this.path);
		}
		//如果 classLoader 不为 null
		else if (this.classLoader != null) {
			//通过 classLoader 来获取 path 的 inputStream
			is = this.classLoader.getResourceAsStream(this.path);
		}
		else {
			//没有指定的 classLoader, 则用系统的
			is = ClassLoader.getSystemResourceAsStream(this.path);
		}
		//如果最终都找不到 path 的 inputStream 则报错
		if (is == null) {
			throw new FileNotFoundException(getDescription() + " cannot be opened because it does not exist");
		}
		return is;
	}

	/**
	 * This implementation returns a URL for the underlying class path resource,
	 * if available.
	 * @see java.lang.ClassLoader#getResource(String)
	 * @see java.lang.Class#getResource(String)
	 */
	@Override
	public URL getURL() throws IOException {
		//获取资源的 URL
		URL url = resolveURL();
		//判断非空
		if (url == null) {
			throw new FileNotFoundException(getDescription() + " cannot be resolved to URL because it does not exist");
		}
		return url;
	}

	/**
	 * This implementation creates a ClassPathResource, applying the given path
	 * relative to the path of the underlying resource of this descriptor.
	 * @see org.springframework.util.StringUtils#applyRelativePath(String, String)
	 */
	@Override
	public Resource createRelative(String relativePath) {
		//拼接基本 path 相对 relativePath 的路径
		String pathToUse = StringUtils.applyRelativePath(this.path, relativePath);
		//如果 class 不为 null, 则创建基于 class 的 ClassPathResource, 否则创建基于 ClassLoader 的 ClassPathResource
		return (this.clazz != null ? new ClassPathResource(pathToUse, this.clazz) :
				new ClassPathResource(pathToUse, this.classLoader));
	}

	/**
	 * This implementation returns the name of the file that this class path
	 * resource refers to.
	 * @see org.springframework.util.StringUtils#getFilename(String)
	 */
	@Override
	@Nullable
	public String getFilename() {
		//获取文件名
		return StringUtils.getFilename(this.path);
	}

	/**
	 * This implementation returns a description that includes the class path location.
	 */
	@Override
	public String getDescription() {
		//创建一个 StringBuilder
		StringBuilder builder = new StringBuilder("class path resource [");
		//获取文件路径
		String pathToUse = this.path;
		//如果 class 不为 null, 且 pathToUse 不以 '/' 开头, 则 path 表示相对路径
		//如果 path 是相对路径, 则获取包路径. 如果 path 是绝对路径, 则不用拼接包路径
		if (this.clazz != null && !pathToUse.startsWith("/")) {
			//添加包路径
			builder.append(ClassUtils.classPackageAsResourcePath(this.clazz));
			//添加 '/'
			builder.append('/');
		}
		//如果 path 是以 '/' 开始
		if (pathToUse.startsWith("/")) {
			//截掉 '/'
			pathToUse = pathToUse.substring(1);
		}
		//将当前的 path
		builder.append(pathToUse);
		//添加 ']'
		builder.append(']');
		//转字符串
		return builder.toString();
	}


	/**
	 * This implementation compares the underlying class path locations.
	 */
	@Override
	public boolean equals(@Nullable Object other) {
		//如果引用一样, 则返回true
		if (this == other) {
			return true;
		}
		//如果不是 ClassPathResource 对象, 则返回 false
		if (!(other instanceof ClassPathResource)) {
			return false;
		}
		//如果是 ClassPathResource 强转
		ClassPathResource otherRes = (ClassPathResource) other;
		// path 和 clazz 和 classLoader 都相等才算相等
		return (this.path.equals(otherRes.path) &&
				ObjectUtils.nullSafeEquals(this.classLoader, otherRes.classLoader) &&
				ObjectUtils.nullSafeEquals(this.clazz, otherRes.clazz));
	}

	/**
	 * This implementation returns the hash code of the underlying
	 * class path location.
	 */
	@Override
	public int hashCode() {
		//返回 path 的 hashCode()
		return this.path.hashCode();
	}

}
