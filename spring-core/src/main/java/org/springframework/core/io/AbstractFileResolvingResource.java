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

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.NoSuchFileException;
import java.nio.file.StandardOpenOption;

import org.springframework.util.ResourceUtils;

/**
 *  资源可以用 URL 来表示的通用逻辑抽象类, 此类的所有方法都是基于 URL 来实现的,  如:
 * - file://dir/dir/abc.txt
 * - http://dir/dir/abc.txt
 * - ftp://abc/ttt.av
 *
 * Abstract base class for resources which resolve URLs into File references,
 * such as {@link UrlResource} or {@link ClassPathResource}.
 *
 * <p>Detects the "file" protocol as well as the JBoss "vfs" protocol in URLs,
 * resolving file system references accordingly.
 *
 * @author Juergen Hoeller
 * @since 3.0
 */
public abstract class AbstractFileResolvingResource extends AbstractResource {

	@Override
	public boolean exists() {
		try {
			//获取资源的 URL 对象
			URL url = getURL();
			//判断是否是文件系统上的文件
			if (ResourceUtils.isFileURL(url)) {
				//如果是, 则返回 File.exists() 的结果
				// Proceed with file system resolution
				return getFile().exists();
			}
			else {
				//尝试处理 url 资源
				//打开一个 url 连接
				// Try a URL connection content-length header
				URLConnection con = url.openConnection();
				//自定义请求连接
				customizeConnection(con);
				//如果是 HttpURLConnection , 则强转成 HttpURLConnection
				HttpURLConnection httpCon =
						(con instanceof HttpURLConnection ? (HttpURLConnection) con : null);
				//如果 httpCon , 则代理 con 是http连接
				if (httpCon != null) {
					//获取响应码
					int code = httpCon.getResponseCode();
					//如果响应码 OK
					if (code == HttpURLConnection.HTTP_OK) {
						//返回 true
						return true;
					}
					//如果返回 404
					else if (code == HttpURLConnection.HTTP_NOT_FOUND) {
						//返回 false
						return false;
					}
				}
				//如果不是 http 请求, 则判断有内容长度是否大于 0
				if (con.getContentLengthLong() > 0) {
					return true;
				}
				//如果是 http 请求
				if (httpCon != null) {
					//没有状态码, 断开连接, 返回 false
					// No HTTP OK status, and no content-length header: give up
					httpCon.disconnect();
					return false;
				}
				//非 http 请求
				else {
					//如果能打开流, 然后关闭, 则也返回 true
					// Fall back to stream existence: can we open the stream?
					getInputStream().close();
					return true;
				}
			}
		}
		catch (IOException ex) {
			//有异常则返回 false
			return false;
		}
	}

	@Override
	public boolean isReadable() {
		try {
			//获取 URL
			URL url = getURL();
			//判断是否是文件系统文件
			if (ResourceUtils.isFileURL(url)) {
				//如果是, 则获取 File
				// Proceed with file system resolution
				File file = getFile();
				//可读, 且非目录则为 true
				return (file.canRead() && !file.isDirectory());
			}
			else {
				//打开一个 url 连接
				// Try InputStream resolution for jar resources
				URLConnection con = url.openConnection();
				//自定义处理, http 连接会将请求设置成 HEAD
				customizeConnection(con);
				//如果是 http 连接
				if (con instanceof HttpURLConnection) {
					//强转
					HttpURLConnection httpCon = (HttpURLConnection) con;
					//获取响应码
					int code = httpCon.getResponseCode();
					//不等于 OK , 则返回 false
					if (code != HttpURLConnection.HTTP_OK) {
						//断开连接
						httpCon.disconnect();
						return false;
					}
				}
				//获取返回的内容长度
				long contentLength = con.getContentLengthLong();
				//大于 0 , 则返回 true
				if (contentLength > 0) {
					return true;
				}
				//内容长度等于 0 则返回 false
				else if (contentLength == 0) {
					// Empty file or directory -> not considered readable...
					return false;
				}
				else {
					//能打开流读取, 则返回 true
					// Fall back to stream existence: can we open the stream?
					getInputStream().close();
					return true;
				}
			}
		}
		catch (IOException ex) {
			//异常返回 false
			return false;
		}
	}

	@Override
	public boolean isFile() {
		try {
			//获取 URL 对象
			URL url = getURL();
			//如果 URL 协议是 VFS 开头的
			if (url.getProtocol().startsWith(ResourceUtils.URL_PROTOCOL_VFS)) {
				//用 url 创建 VfsResource 来判断是否是文件
				return VfsResourceDelegate.getResource(url).isFile();
			}
			//判断 URL 的协议是否是 file
			return ResourceUtils.URL_PROTOCOL_FILE.equals(url.getProtocol());
		}
		catch (IOException ex) {
			//异常返回 false
			return false;
		}
	}

	/**
	 * This implementation returns a File reference for the underlying class path
	 * resource, provided that it refers to a file in the file system.
	 * @see org.springframework.util.ResourceUtils#getFile(java.net.URL, String)
	 */
	@Override
	public File getFile() throws IOException {
		//获取 URL
		URL url = getURL();
		//协议如果是 vfs 开头的
		if (url.getProtocol().startsWith(ResourceUtils.URL_PROTOCOL_VFS)) {
			//创建 vfsResource , 调用 VfsResource.getFile()
			return VfsResourceDelegate.getResource(url).getFile();
		}
		//创建 File 对象返回
		return ResourceUtils.getFile(url, getDescription());
	}

	/**
	 * This implementation determines the underlying File
	 * (or jar file, in case of a resource in a jar/zip).
	 */
	@Override
	protected File getFileForLastModifiedCheck() throws IOException {
		//获取 URL
		URL url = getURL();
		//判断文件是否为 Jar url
		if (ResourceUtils.isJarURL(url)) {
			//如果是 jar 文件url, 则获取真正的 URL
			URL actualUrl = ResourceUtils.extractArchiveURL(url);
			//协议如果是 vfs 开头的
			if (actualUrl.getProtocol().startsWith(ResourceUtils.URL_PROTOCOL_VFS)) {
				//创建 vfsResource , 调用 VfsResource.getFile()
				return VfsResourceDelegate.getResource(actualUrl).getFile();
			}
			//普通文件, 创建 File 对象返回
			return ResourceUtils.getFile(actualUrl, "Jar URL");
		}
		else {
			//普通文件 url , 直接 getFile()
			return getFile();
		}
	}

	/**
	 * This implementation returns a File reference for the given URI-identified
	 * resource, provided that it refers to a file in the file system.
	 * @since 5.0
	 * @see #getFile(URI)
	 */
	protected boolean isFile(URI uri) {
		try {
			//如果是 vfs 开头的
			if (uri.getScheme().startsWith(ResourceUtils.URL_PROTOCOL_VFS)) {
				//创建 vfsResource 来判断
				return VfsResourceDelegate.getResource(uri).isFile();
			}
			//判断协议是否是以 file 开头的
			return ResourceUtils.URL_PROTOCOL_FILE.equals(uri.getScheme());
		}
		catch (IOException ex) {
			//异常返回 false
			return false;
		}
	}

	/**
	 * This implementation returns a File reference for the given URI-identified
	 * resource, provided that it refers to a file in the file system.
	 * @see org.springframework.util.ResourceUtils#getFile(java.net.URI, String)
	 */
	protected File getFile(URI uri) throws IOException {
		//根据文件协议, 用不同的方式创建文件
		if (uri.getScheme().startsWith(ResourceUtils.URL_PROTOCOL_VFS)) {
			return VfsResourceDelegate.getResource(uri).getFile();
		}
		return ResourceUtils.getFile(uri, getDescription());
	}

	/**
	 * This implementation returns a FileChannel for the given URI-identified
	 * resource, provided that it refers to a file in the file system.
	 * @since 5.0
	 * @see #getFile()
	 */
	@Override
	public ReadableByteChannel readableChannel() throws IOException {
		try {
			//尝试用系统文件打开 Channel
			// Try file system channel
			return FileChannel.open(getFile().toPath(), StandardOpenOption.READ);
		}
		catch (FileNotFoundException | NoSuchFileException ex) {
			//打不开就用父类的, 用流打开
			// Fall back to InputStream adaptation in superclass
			return super.readableChannel();
		}
	}

	@Override
	public long contentLength() throws IOException {
		//获取 URL
		URL url = getURL();
		//如果是文件 URL
		if (ResourceUtils.isFileURL(url)) {
			//获取文件
			// Proceed with file system resolution
			File file = getFile();
			//获取文件长度
			long length = file.length();
			//长度为0 且文件不存在, 则报错
			if (length == 0L && !file.exists()) {
				throw new FileNotFoundException(getDescription() +
						" cannot be resolved in the file system for checking its content length");
			}
			return length;
		}
		else {
			//尝试打开 URL Connection 来获取
			// Try a URL connection content-length header
			URLConnection con = url.openConnection();
			customizeConnection(con);
			return con.getContentLengthLong();
		}
	}

	@Override
	public long lastModified() throws IOException {
		//获取文件 URL
		URL url = getURL();
		boolean fileCheck = false;
		//如果是文件的URL, 或者是 Jar 的url
		if (ResourceUtils.isFileURL(url) || ResourceUtils.isJarURL(url)) {
			// Proceed with file system resolution
			fileCheck = true;
			try {
				//获取文件
				File fileToCheck = getFileForLastModifiedCheck();
				//获取文件的最后修改时间
				long lastModified = fileToCheck.lastModified();
				//如果最后修改时间大于 0, 且文件存在
				if (lastModified > 0L || fileToCheck.exists()) {
					//返回
					return lastModified;
				}
			}
			catch (FileNotFoundException ex) {
				//文件系统检查不出最后更新时间, 退而求次, 从 URL Connection 中获取
				// Defensively fall back to URL connection check instead
			}
		}
		//打开 URL 连接
		// Try a URL connection last-modified header
		URLConnection con = url.openConnection();
		//自定义
		customizeConnection(con);
		//获取最后修改时间
		long lastModified = con.getLastModified();
		//URL Connection 中获取不了, 且已经做了文件的检查, 也获取不了, 则报异常
		if (fileCheck && lastModified == 0 && con.getContentLengthLong() <= 0) {
			throw new FileNotFoundException(getDescription() +
					" cannot be resolved in the file system for checking its last-modified timestamp");
		}
		//返回获取到的 最后更新时间
		return lastModified;
	}

	/**
	 * Customize the given {@link URLConnection}, obtained in the course of an
	 * {@link #exists()}, {@link #contentLength()} or {@link #lastModified()} call.
	 * <p>Calls {@link ResourceUtils#useCachesIfNecessary(URLConnection)} and
	 * delegates to {@link #customizeConnection(HttpURLConnection)} if possible.
	 * Can be overridden in subclasses.
	 * @param con the URLConnection to customize
	 * @throws IOException if thrown from URLConnection methods
	 */
	protected void customizeConnection(URLConnection con) throws IOException {
		//如果有必要的话, 则设置使用缓存
		ResourceUtils.useCachesIfNecessary(con);
		//如果是 http 连接
		if (con instanceof HttpURLConnection) {
			//主要是设置求方式
			customizeConnection((HttpURLConnection) con);
		}
	}

	/**
	 * Customize the given {@link HttpURLConnection}, obtained in the course of an
	 * {@link #exists()}, {@link #contentLength()} or {@link #lastModified()} call.
	 * <p>Sets request method "HEAD" by default. Can be overridden in subclasses.
	 * @param con the HttpURLConnection to customize
	 * @throws IOException if thrown from HttpURLConnection methods
	 */
	protected void customizeConnection(HttpURLConnection con) throws IOException {
		//设置请求为 HEAD 类型, 默认是 GET 请求
		con.setRequestMethod("HEAD");
	}


	/**
	 * 创建 VfsResource 的工具类
	 * Inner delegate class, avoiding a hard JBoss VFS API dependency at runtime.
	 */
	private static class VfsResourceDelegate {

		public static Resource getResource(URL url) throws IOException {
			return new VfsResource(VfsUtils.getRoot(url));
		}

		public static Resource getResource(URI uri) throws IOException {
			return new VfsResource(VfsUtils.getRoot(uri));
		}
	}

}
