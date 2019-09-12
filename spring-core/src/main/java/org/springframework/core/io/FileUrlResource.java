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
import java.io.IOException;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.channels.FileChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;

import org.springframework.lang.Nullable;
import org.springframework.util.ResourceUtils;

/**
 * URL 形式的 文件资源
 * Subclass of {@link UrlResource} which assumes file resolution, to the degree
 * of implementing the {@link WritableResource} interface for it. This resource
 * variant also caches resolved {@link File} handles from {@link #getFile()}.
 *
 * <p>This is the class resolved by {@link DefaultResourceLoader} for a "file:..."
 * URL location, allowing a downcast to {@link WritableResource} for it.
 *
 * <p>Alternatively, for direct construction from a {@link java.io.File} handle
 * or NIO {@link java.nio.file.Path}, consider using {@link FileSystemResource}.
 *
 * @author Juergen Hoeller
 * @since 5.0.2
 */
public class FileUrlResource extends UrlResource implements WritableResource {

	/**
	 * URL 表示的文件
	 */
	@Nullable
	private volatile File file;


	/**
	 * Create a new {@code FileUrlResource} based on the given URL object.
	 * <p>Note that this does not enforce "file" as URL protocol. If a protocol
	 * is known to be resolvable to a file,
	 * @param url a URL
	 * @see ResourceUtils#isFileURL(URL)
	 * @see #getFile()
	 */
	public FileUrlResource(URL url) {
		super(url);
	}

	/**
	 * Create a new {@code FileUrlResource} based on the given file location,
	 * using the URL protocol "file".
	 * <p>The given parts will automatically get encoded if necessary.
	 * @param location the location (i.e. the file path within that protocol)
	 * @throws MalformedURLException if the given URL specification is not valid
	 * @see UrlResource#UrlResource(String, String)
	 * @see ResourceUtils#URL_PROTOCOL_FILE
	 */
	public FileUrlResource(String location) throws MalformedURLException {
		super(ResourceUtils.URL_PROTOCOL_FILE, location);
	}


	@Override
	public File getFile() throws IOException {
		//获取缓存 File 对象
		File file = this.file;
		//如果对象不为 null
		if (file != null) {
			//直接返回
			return file;
		}
		//通过父类, 获取文件对象
		file = super.getFile();
		//缓存起来
		this.file = file;
		//返回
		return file;
	}

	@Override
	public boolean isWritable() {
		try {
			//获取 URL
			URL url = getURL();
			//判断是否文件类型
			if (ResourceUtils.isFileURL(url)) {
				//获取文件
				// Proceed with file system resolution
				File file = getFile();
				//判断是否可写
				return (file.canWrite() && !file.isDirectory());
			}
			else {
				//非文件类型的 URL 默认都是可写的
				return true;
			}
		}
		catch (IOException ex) {
			return false;
		}
	}

	@Override
	public OutputStream getOutputStream() throws IOException {
		//创建文件的输出流
		return Files.newOutputStream(getFile().toPath());
	}

	@Override
	public WritableByteChannel writableChannel() throws IOException {
		//创建 WritableByteChannel
		return FileChannel.open(getFile().toPath(), StandardOpenOption.WRITE);
	}

	@Override
	public Resource createRelative(String relativePath) throws MalformedURLException {
		//如果有 '/' 开头, 则截掉
		if (relativePath.startsWith("/")) {
			relativePath = relativePath.substring(1);
		}
		//创建 FileUrlResource
		return new FileUrlResource(new URL(getURL(), relativePath));
	}

}
