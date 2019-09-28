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

package org.springframework.util.xml;

import java.io.BufferedReader;
import java.io.CharConversionException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

import org.springframework.lang.Nullable;
import org.springframework.util.StringUtils;

/**
 * XML 验证模式探测器, 主要用于获取 xml 文件的校验类型, 主要判断是不是 dtd , 如果不是则为 xsd
 * 注意当遇到下面的情况是处理不了的(注释弄成多行):
 * <!DOCTYPE beans PUBLIC "-//SPRING//DTD BEAN 2.0//EN" "https://www.springframework.org/dtd/spring-beans-2.0.dtd"><!-- trailing
 * comment -->
 * 对于这种跨行的情况是没办法正确解析的, 感觉这是这个类的 bug, 这个类也不是百分之百能处理所有的情况.(我将正确的写法写到注释里面去)
 * - 正确的处理思路是, 要返回每一行中除了注释之外的内容, 只要有内容都应该进行下面是否有 DOCTYPE 逻辑的判断
 *
 * Detects whether an XML stream is using DTD- or XSD-based validation.
 *
 * @author Rob Harrop
 * @author Juergen Hoeller
 * @author Sam Brannen
 * @since 2.0
 */
public class XmlValidationModeDetector {

	/**
	 * 禁用验证
	 * Indicates that the validation should be disabled.
	 */
	public static final int VALIDATION_NONE = 0;

	/**
	 * 自动校验
	 * Indicates that the validation mode should be auto-guessed, since we cannot find
	 * a clear indication (probably choked on some special characters, or the like).
	 */
	public static final int VALIDATION_AUTO = 1;

	/**
	 * DTD 验证
	 * Indicates that DTD validation should be used (we found a "DOCTYPE" declaration).
	 */
	public static final int VALIDATION_DTD = 2;

	/**
	 * XSD 验证
	 * Indicates that XSD validation should be used (found no "DOCTYPE" declaration).
	 */
	public static final int VALIDATION_XSD = 3;


	/**
	 * The token in a XML document that declares the DTD to use for validation
	 * and thus that DTD validation is being used.
	 */
	private static final String DOCTYPE = "DOCTYPE";

	/**
	 * 表示 XML 注释开始的标记
	 * The token that indicates the start of an XML comment.
	 */
	private static final String START_COMMENT = "<!--";

	/**
	 * 表示 XML 注释结束的标记
	 * The token that indicates the end of an XML comment.
	 */
	private static final String END_COMMENT = "-->";


	/**
	 * 表明当前解析的位置是否是注释
	 * Indicates whether or not the current parse position is inside an XML comment.
	 */
	private boolean inComment;


	/**
	 * Detect the validation mode for the XML document in the supplied {@link InputStream}.
	 * Note that the supplied {@link InputStream} is closed by this method before returning.
	 * @param inputStream the InputStream to parse
	 * @throws IOException in case of I/O failure
	 * @see #VALIDATION_DTD
	 * @see #VALIDATION_XSD
	 */
	public int detectValidationMode(InputStream inputStream) throws IOException {
		//创建 BufferedReader
		// Peek into the file to look for DOCTYPE.
		BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
		try {
			//是否为 DTD 校验模式. 默认为, 非 DTD 模式, 即 XSD 模式
			boolean isDtdValidated = false;
			String content;
			//循环, 逐行读取 XML 文件的内容
			while ((content = reader.readLine()) != null) {
				//获取当前的文本
				content = consumeCommentTokens(content);

				//====> wolfleong 觉得正确的处理
				//if(!StringUtils.hasText(content)){
				//  continue;
				// }
				//====>
				//如果是注释 或 文为空
				if (this.inComment || !StringUtils.hasText(content)) {
					//不做处理, 读下一行
					continue;
				}
				//判断 当前行是否有 DOCTYPE 这个字符
				if (hasDoctype(content)) {
					//如果有, 则表是是 Dtd 验证
					isDtdValidated = true;
					//退循环
					break;
				}
				//如果有 XML 内容的开始标识
				if (hasOpeningTag(content)) {
					//直接退出, 因为 <!DOCTYPE 只能在内容前面
					// End of meaningful data...
					break;
				}
			}
			//如果是 DTD 验证, 则返回 VALIDATION_DTD , 否则返回 VALIDATION_XSD
			return (isDtdValidated ? VALIDATION_DTD : VALIDATION_XSD);
		}
		catch (CharConversionException ex) {
			//报异常, 返回默认的自动校验
			// Choked on some character encoding...
			// Leave the decision up to the caller.
			return VALIDATION_AUTO;
		}
		finally {
			//关闭流
			reader.close();
		}
	}


	/**
	 * Does the content contain the DTD DOCTYPE declaration?
	 */
	private boolean hasDoctype(String content) {
		return content.contains(DOCTYPE);
	}

	/**
	 * 判断当前内容是包含有 XML 的开始标签
	 * Does the supplied content contain an XML opening tag. If the parse state is currently
	 * in an XML comment then this method always returns false. It is expected that all comment
	 * tokens will have consumed for the supplied content before passing the remainder to this method.
	 */
	private boolean hasOpeningTag(String content) {
		//如果当前文本在注释中, 则返回 false
		if (this.inComment) {
			return false;
		}
		//获取 < 这个字符的位置
		int openTagIndex = content.indexOf('<');
		//如果有 < 这个字符 , 且 < 后面的内容的长度少至少大于 1, 且第一个字符是字母
		return (openTagIndex > -1 && (content.length() > openTagIndex + 1) &&
				Character.isLetter(content.charAt(openTagIndex + 1)));
	}

	/**
	 * 忽略掉注释内容, 返回行的正文
	 * - 注意, 如果当前行只有 START_COMMENT 开始标识, 而没有结束标识, 那么当前行就返回 null
	 * - 只有当前行中以 END_COMMENT 结尾, 才有内容返回
	 * - 理论上, 注释的内容只能存在 DOCTYPE 标签前面, 在注释外面是不可能存在没有标签的字符, 也就是注释开始前是没有字符的, 注释后面也没有,
	 *   最多就紧接着 DOCTYPE 这个标签内容
	 * Consume all leading and trailing comments in the given String and return
	 * the remaining content, which may be empty since the supplied content might
	 * be all comment data.
	 */
	@Nullable
	private String consumeCommentTokens(String line) {
		//获取注释开始标识的索引
		int indexOfStartComment = line.indexOf(START_COMMENT);
		//如果注释开始标识的索引不存在且行不包括注释结束标识
		if (indexOfStartComment == -1 && !line.contains(END_COMMENT)) {
			//====> wolfleong 觉得正确的处理
//			if(this.inComment){
//				return null;
//			}
			//====>
			//返回行, 有可能整行是内容, 也有可能整行是注释
			return line;
		}

		String result = "";
		//剩下要处理的行内容
		String currLine = line;
		//如果有注释的标开始标识
		if (indexOfStartComment >= 0) {
			//获取注释前的内容(开始标识前的内容是正文)
			result = line.substring(0, indexOfStartComment);
			//获取注释的开始标识之后的内容(包括开始注释的标识)
			currLine = line.substring(indexOfStartComment);
		}

		//循环处理注释的内容, currLine 表示处理完一个注释标签后, 剩余的内容
		while ((currLine = consume(currLine)) != null) {
			//====> wolfleong 觉得正确的处理
//			if (!this.inComment) {
//				//合并内容返回
//				int index = currLine.indexOf(START_COMMENT);
//				if(index != -1){
//					result =  result + currLine.substring(0, index);
//				}else {
//					result = result + currLine;
//				}
//			}
			//====>
			//如果当前的内容不是在注释中, 且内容前面不是注释标签开始标识开头的, 直接返回剩下的内容
			if (!this.inComment && !currLine.trim().startsWith(START_COMMENT)) {
				//合并内容返回
				return result + currLine;
			}
		}
		//wolfleong 觉得正确的处理
//		if(StringUtils.hasText(result)){
//			return result;
//		}
		//没有内容就返回 null
		return null;
	}

	/**
	 * Consume the next comment token, update the "inComment" flag
	 * and return the remaining content.
	 */
	@Nullable
	private String consume(String line) {
		//如果当前行是注释, 则获取结束注释的标记的索引. 如果当前行不是注释中, 则获取注释开始标记的索引
		int index = (this.inComment ? endComment(line) : startComment(line));
		//如果有索引, 则获取索引后面的内容, 也就是剩下的内容
		return (index == -1 ? null : line.substring(index));
	}

	/**
	 * Try to consume the {@link #START_COMMENT} token.
	 * @see #commentToken(String, String, boolean)
	 */
	private int startComment(String line) {
		return commentToken(line, START_COMMENT, true);
	}

	/**
	 *
	 * @return 返回结束评论的位置索引
	 */
	private int endComment(String line) {
		return commentToken(line, END_COMMENT, false);
	}

	/**
	 * Try to consume the supplied token against the supplied content and update the
	 * in comment parse state to the supplied value. Returns the index into the content
	 * which is after the token or -1 if the token is not found.
	 */
	private int commentToken(String line, String token, boolean inCommentIfPresent) {
		//判断有没有指定的 token 的索引
		int index = line.indexOf(token);
		// 如果有 token 索引
		if (index > - 1) {
			//设置当前行是否是注释
			this.inComment = inCommentIfPresent;
		}
		//如果 index 有值, 则返回 token 的结束的位置
		return (index == -1 ? index : index + token.length());
	}

}
