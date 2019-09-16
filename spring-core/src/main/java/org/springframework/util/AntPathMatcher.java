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

package org.springframework.util;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.lang.Nullable;

/**
 * {@link PathMatcher} implementation for Ant-style path patterns.
 *
 * <p>Part of this mapping code has been kindly borrowed from <a href="https://ant.apache.org">Apache Ant</a>.
 *
 * <p>The mapping matches URLs using the following rules:<br>
 * <ul>
 * <li>{@code ?} matches one character</li>
 * <li>{@code *} matches zero or more characters</li>
 * <li>{@code **} matches zero or more <em>directories</em> in a path</li>
 * <li>{@code {spring:[a-z]+}} matches the regexp {@code [a-z]+} as a path variable named "spring"</li>
 * </ul>
 *
 * <h3>Examples</h3>
 * <ul>
 * <li>{@code com/t?st.jsp} &mdash; matches {@code com/test.jsp} but also
 * {@code com/tast.jsp} or {@code com/txst.jsp}</li>
 * <li>{@code com/*.jsp} &mdash; matches all {@code .jsp} files in the
 * {@code com} directory</li>
 * <li><code>com/&#42;&#42;/test.jsp</code> &mdash; matches all {@code test.jsp}
 * files underneath the {@code com} path</li>
 * <li><code>org/springframework/&#42;&#42;/*.jsp</code> &mdash; matches all
 * {@code .jsp} files underneath the {@code org/springframework} path</li>
 * <li><code>org/&#42;&#42;/servlet/bla.jsp</code> &mdash; matches
 * {@code org/springframework/servlet/bla.jsp} but also
 * {@code org/springframework/testing/servlet/bla.jsp} and {@code org/servlet/bla.jsp}</li>
 * <li>{@code com/{filename:\\w+}.jsp} will match {@code com/test.jsp} and assign the value {@code test}
 * to the {@code filename} variable</li>
 * </ul>
 *
 * <p><strong>Note:</strong> a pattern and a path must both be absolute or must
 * both be relative in order for the two to match. Therefore it is recommended
 * that users of this implementation to sanitize patterns in order to prefix
 * them with "/" as it makes sense in the context in which they're used.
 *
 * @author Alef Arendsen
 * @author Juergen Hoeller
 * @author Rob Harrop
 * @author Arjen Poutsma
 * @author Rossen Stoyanchev
 * @author Sam Brannen
 * @since 16.07.2003
 */
public class AntPathMatcher implements PathMatcher {

	//默认路径分割符
	/** Default path separator: "/". */
	public static final String DEFAULT_PATH_SEPARATOR = "/";

	/**
	 * 缓存关闭阈值
	 */
	private static final int CACHE_TURNOFF_THRESHOLD = 65536;

	private static final Pattern VARIABLE_PATTERN = Pattern.compile("\\{[^/]+?\\}");

	private static final char[] WILDCARD_CHARS = { '*', '?', '{' };


	/**
	 * 路径分割符
	 */
	private String pathSeparator;

	/**
	 * 路径分割缓存
	 */
	private PathSeparatorPatternCache pathSeparatorPatternCache;

	/**
	 * 是否区分大小写
	 */
	private boolean caseSensitive = true;

	/**
	 * 是否去除空白字符
	 */
	private boolean trimTokens = false;

	/**
	 * true 则激活无限模式缓存. false 完全关闭模式缓存
	 */
	@Nullable
	private volatile Boolean cachePatterns;

	/**
	 * pattern 分词的缓存, key 为 pattern, val 为分词后的字符串数组
	 */
	private final Map<String, String[]> tokenizedPatternCache = new ConcurrentHashMap<>(256);

	final Map<String, AntPathStringMatcher> stringMatcherCache = new ConcurrentHashMap<>(256);


	/**
	 * Create a new instance with the {@link #DEFAULT_PATH_SEPARATOR}.
	 */
	public AntPathMatcher() {
		//默认路径分割符
		this.pathSeparator = DEFAULT_PATH_SEPARATOR;
		//创建 PathSeparatorPatternCache
		this.pathSeparatorPatternCache = new PathSeparatorPatternCache(DEFAULT_PATH_SEPARATOR);
	}

	/**
	 * A convenient, alternative constructor to use with a custom path separator.
	 * @param pathSeparator the path separator to use, must not be {@code null}.
	 * @since 4.1
	 */
	public AntPathMatcher(String pathSeparator) {
		Assert.notNull(pathSeparator, "'pathSeparator' is required");
		this.pathSeparator = pathSeparator;
		this.pathSeparatorPatternCache = new PathSeparatorPatternCache(pathSeparator);
	}


	/**
	 * Set the path separator to use for pattern parsing.
	 * <p>Default is "/", as in Ant.
	 */
	public void setPathSeparator(@Nullable String pathSeparator) {
		this.pathSeparator = (pathSeparator != null ? pathSeparator : DEFAULT_PATH_SEPARATOR);
		this.pathSeparatorPatternCache = new PathSeparatorPatternCache(this.pathSeparator);
	}

	/**
	 * Specify whether to perform pattern matching in a case-sensitive fashion.
	 * <p>Default is {@code true}. Switch this to {@code false} for case-insensitive matching.
	 * @since 4.2
	 */
	public void setCaseSensitive(boolean caseSensitive) {
		this.caseSensitive = caseSensitive;
	}

	/**
	 * Specify whether to trim tokenized paths and patterns.
	 * <p>Default is {@code false}.
	 */
	public void setTrimTokens(boolean trimTokens) {
		this.trimTokens = trimTokens;
	}

	/**
	 * Specify whether to cache parsed pattern metadata for patterns passed
	 * into this matcher's {@link #match} method. A value of {@code true}
	 * activates an unlimited pattern cache; a value of {@code false} turns
	 * the pattern cache off completely.
	 * <p>Default is for the cache to be on, but with the variant to automatically
	 * turn it off when encountering too many patterns to cache at runtime
	 * (the threshold is 65536), assuming that arbitrary permutations of patterns
	 * are coming in, with little chance for encountering a recurring pattern.
	 * @since 4.0.1
	 * @see #getStringMatcher(String)
	 */
	public void setCachePatterns(boolean cachePatterns) {
		this.cachePatterns = cachePatterns;
	}

	private void deactivatePatternCache() {
		this.cachePatterns = false;
		this.tokenizedPatternCache.clear();
		this.stringMatcherCache.clear();
	}


	@Override
	public boolean isPattern(@Nullable String path) {
		//如果 path 为 null, 则返回false
		if (path == null) {
			return false;
		}
		//记录是否有路径变量
		boolean uriVar = false;
		//遍历字符串
		for (int i = 0; i < path.length(); i++) {
			//获取字符
			char c = path.charAt(i);
			//如果字符中有 * 或 ? , 则返回true
			if (c == '*' || c == '?') {
				return true;
			}
			//如果有 { 则记录
			if (c == '{') {
				//记录
				uriVar = true;
				continue;
			}
			//如果有 } , 且 uriVar 为 true , 则表明有路径变量
			if (c == '}' && uriVar) {
				return true;
			}
		}
		return false;
	}

	@Override
	public boolean match(String pattern, String path) {
		return doMatch(pattern, path, true, null);
	}

	@Override
	public boolean matchStart(String pattern, String path) {
		return doMatch(pattern, path, false, null);
	}

	/**
	 * Actually match the given {@code path} against the given {@code pattern}.
	 * @param pattern the pattern to match against
	 * @param path the path to test
	 * @param fullMatch whether a full pattern match is required (else a pattern match
	 * as far as the given base path goes is sufficient)
	 * @return {@code true} if the supplied {@code path} matched, {@code false} if it didn't
	 */
	protected boolean doMatch(String pattern, @Nullable String path, boolean fullMatch,
			@Nullable Map<String, String> uriTemplateVariables) {

		//path为 null, 或 path 和 pattern 前缀不一样, 则返回 false
		if (path == null || path.startsWith(this.pathSeparator) != pattern.startsWith(this.pathSeparator)) {
			return false;
		}

		//对 pattern 进行分词, 获取 pattern 的数组
		String[] pattDirs = tokenizePattern(pattern);
		if (fullMatch && this.caseSensitive && !isPotentialMatch(path, pattDirs)) {
			return false;
		}

		//对 path 进行分词, 获取 path 的数组
		String[] pathDirs = tokenizePath(path);
		int pattIdxStart = 0;
		int pattIdxEnd = pattDirs.length - 1;
		int pathIdxStart = 0;
		int pathIdxEnd = pathDirs.length - 1;

		//从前面开始遍历
		// Match all elements up to the first **
		while (pattIdxStart <= pattIdxEnd && pathIdxStart <= pathIdxEnd) {
			//获取第一个 pattern
			String pattDir = pattDirs[pattIdxStart];
			//如果是 **
			if ("**".equals(pattDir)) {
				//退出
				break;
			}
			//如果不是 ** , 则匹配
			if (!matchStrings(pattDir, pathDirs[pathIdxStart], uriTemplateVariables)) {
				//不匹配, 返回 false
				return false;
			}
			//索引自增, 处理下一个
			pattIdxStart++;
			pathIdxStart++;
		}

		//走到这步, 有三种情况 pattIdxStart > pattIdxEnd 或 pathIdxStart > pathIdxEnd 或遇到 **
		//如果 path 遍历完了
		if (pathIdxStart > pathIdxEnd) {
			//如果同时, pattern 也遍历完了, 那就是 path 和 pattern 一样长
			// Path is exhausted, only match if rest of pattern is * or **'s
			if (pattIdxStart > pattIdxEnd) {
				//那判断最后一个字符是不是 /, 如果是, 则都要以 / 结速
				return (pattern.endsWith(this.pathSeparator) == path.endsWith(this.pathSeparator));
			}
			//如果非全匹配
			if (!fullMatch) {
				//返回 true
				return true;
			}
			//如果 pattern 刚好到最后一个, 且 pattern 最后一个是 * 且 path 以 / 结束, 如 /ab/*, /ab/
			if (pattIdxStart == pattIdxEnd && pattDirs[pattIdxStart].equals("*") && path.endsWith(this.pathSeparator)) {
				return true;
			}
			//遍历剩下的 pattern
			for (int i = pattIdxStart; i <= pattIdxEnd; i++) {
				//只要有一个不是 ** , 就返回 false
				if (!pattDirs[i].equals("**")) {
					return false;
				}
			}
			//最后返回 true , 肯定是 /ab/**, /ab/ 还有 /ab/**/**, /ab/ 这种情况
			return true;
		}
		//如果 pattern 先遍历完了, 则表示, 没有匹配上, 因为
		else if (pattIdxStart > pattIdxEnd) {
			// String not exhausted, but pattern is. Failure.
			return false;
		}
		//遇到 **, 退出的循环
		else if (!fullMatch && "**".equals(pattDirs[pattIdxStart])) {
			// Path start definitely matches due to "**" part in pattern.
			return true;
		}

		//从后面开始遍历, 直到 ** 为止
		// up to last '**'
		while (pattIdxStart <= pattIdxEnd && pathIdxStart <= pathIdxEnd) {
			//获取 pattern
			String pattDir = pattDirs[pattIdxEnd];
			//如果遇到 ** , 则退出
			if (pattDir.equals("**")) {
				break;
			}
			//如果不匹配, 则返回false
			if (!matchStrings(pattDir, pathDirs[pathIdxEnd], uriTemplateVariables)) {
				return false;
			}
			//自减, 处理下一个
			pattIdxEnd--;
			pathIdxEnd--;
		}
		//退出循环时, 有三种情况
		// 但是有一种情况是永远不可以出现的, pattIdxStart > pattIdxEnd, 因为最差的情况时,
		// 当 pattIdxStart == pattIdxEnd 时, pattern 为 **, 如: /a/**/b/c, /a/e/f/t/b/c
		//当 pathIdxStart > pathIdxEnd 时, 也就是 path 后面的路径比 pattern 少, 如:  /a/**/b/c, /a/b/c 或 /a/**/**/b/c, /a/b/c
		if (pathIdxStart > pathIdxEnd) {
			// 如:  /a/**/**/b/c, /a/b/c
			//按上面的, pattIdxStart = 1, pattIdxEnd = 2, pathIdxStart = 1, pathIdxEnd = 0
			//遍历 pattern , 索引在 1 到 2 之间必须要全部为 ** 才行, 否则为 false
			// String is exhausted
			for (int i = pattIdxStart; i <= pattIdxEnd; i++) {
				if (!pattDirs[i].equals("**")) {
					return false;
				}
			}
			return true;
		}

		//下来这里的情况只有一种, 上面的 while 遇到 ** 退出了
		//如果 pattern 只有一个 ** , 那么 pattIdxStart 肯定等于 pattIdxEnd
		// 当 pattIdxStart != pattIdxEnd 时, 即表明 pattern 不止有一个 **
		while (pattIdxStart != pattIdxEnd && pathIdxStart <= pathIdxEnd) {
			//记录除正向第一个 ** 外的 ** 的索引, pattIdxStart 为第一个 ** 的索引
			int patIdxTmp = -1;
			//从 pattIdxStart 下一个开始遍历, 直到有一个 **
			for (int i = pattIdxStart + 1; i <= pattIdxEnd; i++) {
				if (pattDirs[i].equals("**")) {
					patIdxTmp = i;
					break;
				}
			}
			//当前一个 ** 跟下一个 ** 是挨着的, 即 **/**
			if (patIdxTmp == pattIdxStart + 1) {
				//pattIdxStart 加 1, 下一次循环
				// '**/**' situation, so skip one
				pattIdxStart++;
				continue;
			}
			//这种情况就是, /a/**/c/d/**/f/g 和 /a/b/c/d/e/f/g
			//两个 **, 中间的 pattern 和 path 的长度
			// Find the pattern between padIdxStart & padIdxTmp in str between
			// strIdxStart & strIdxEnd
			//获取两个 ** 中间 path 的长度
			int patLength = (patIdxTmp - pattIdxStart - 1);
			//获取两个 ** 位置的 path 的长度
			int strLength = (pathIdxEnd - pathIdxStart + 1);
			int foundIdx = -1;

			//为什么取 strLength 与 patLength 的差值呢, 因为要在 str 中做 pattern 的窗口移动,
			// strLength 每比 pattern 多一个 path, 就多一次窗口比较的次数, 也就是窗口数
			strLoop:
			for (int i = 0; i <= strLength - patLength; i++) {
				//以 pattern  中的 path 的个数来做窗口移动
				for (int j = 0; j < patLength; j++) {
					//获取 ** 后一个 pattern
					String subPat = pattDirs[pattIdxStart + j + 1];
					//获取 ** 位置的 path
					String subStr = pathDirs[pathIdxStart + i + j];
					//比较, 如果不相等, 则切换窗口
					if (!matchStrings(subPat, subStr, uriTemplateVariables)) {
						//去下一个窗口
						continue strLoop;
					}
				}
				//只要找到一个窗口就直接退出
				foundIdx = pathIdxStart + i;
				break;
			}

			//如果没有找到, 则返回 false
			if (foundIdx == -1) {
				return false;
			}

			//记录下一个 ** pattern 的索引
			pattIdxStart = patIdxTmp;
			//记录下一个 ** 的 path 的索引
			pathIdxStart = foundIdx + patLength;
		}

		//当 pathIdxStart > pathIdxEnd 会进入这里, /a/**/c/d/**/**/f/g 和 /a/c/d/f/g todo wolfleong 但相不到什么情况会进入这里
		//当 pattIdxStart == pattIdxEnd 会直接进入这里
		for (int i = pattIdxStart; i <= pattIdxEnd; i++) {
			if (!pattDirs[i].equals("**")) {
				return false;
			}
		}

		return true;
	}

	private boolean isPotentialMatch(String path, String[] pattDirs) {
		if (!this.trimTokens) {
			int pos = 0;
			for (String pattDir : pattDirs) {
				int skipped = skipSeparator(path, pos, this.pathSeparator);
				pos += skipped;
				skipped = skipSegment(path, pos, pattDir);
				if (skipped < pattDir.length()) {
					return (skipped > 0 || (pattDir.length() > 0 && isWildcardChar(pattDir.charAt(0))));
				}
				pos += skipped;
			}
		}
		return true;
	}

	private int skipSegment(String path, int pos, String prefix) {
		int skipped = 0;
		for (int i = 0; i < prefix.length(); i++) {
			char c = prefix.charAt(i);
			if (isWildcardChar(c)) {
				return skipped;
			}
			int currPos = pos + skipped;
			if (currPos >= path.length()) {
				return 0;
			}
			if (c == path.charAt(currPos)) {
				skipped++;
			}
		}
		return skipped;
	}

	private int skipSeparator(String path, int pos, String separator) {
		int skipped = 0;
		while (path.startsWith(separator, pos + skipped)) {
			skipped += separator.length();
		}
		return skipped;
	}

	private boolean isWildcardChar(char c) {
		for (char candidate : WILDCARD_CHARS) {
			if (c == candidate) {
				return true;
			}
		}
		return false;
	}

	/**
	 * tokenizePattern 与 tokenizePath 的区别是 , tokenizePattern 多了缓存处理
	 * Tokenize the given path pattern into parts, based on this matcher's settings.
	 * <p>Performs caching based on {@link #setCachePatterns}, delegating to
	 * {@link #tokenizePath(String)} for the actual tokenization algorithm.
	 * @param pattern the pattern to tokenize
	 * @return the tokenized pattern parts
	 */
	protected String[] tokenizePattern(String pattern) {
		String[] tokenized = null;
		//获取是否缓存 pattern
		Boolean cachePatterns = this.cachePatterns;
		//如果 cachePatterns 为 null 或者为 true
		if (cachePatterns == null || cachePatterns.booleanValue()) {
			//根据 pattern 从缓存中获取 tokenized
			tokenized = this.tokenizedPatternCache.get(pattern);
		}
		//如果 tokenized 为 null
		if (tokenized == null) {
			//将 pattern 分词
			tokenized = tokenizePath(pattern);
			//如果 cachePatterns 为 null 且 缓存个数已经达到阀值
			if (cachePatterns == null && this.tokenizedPatternCache.size() >= CACHE_TURNOFF_THRESHOLD) {
				// Try to adapt to the runtime situation that we're encountering:
				// There are obviously too many different patterns coming in here...
				// So let's turn off the cache since the patterns are unlikely to be reoccurring.
				//关闭缓存并且清空缓存
				deactivatePatternCache();
				//返回当前 tokenized
				return tokenized;
			}
			//如果有开缓存, 则将结果添加到缓存
			if (cachePatterns == null || cachePatterns.booleanValue()) {
				this.tokenizedPatternCache.put(pattern, tokenized);
			}
		}
		//返回分词结果
		return tokenized;
	}

	/**
	 * Tokenize the given path into parts, based on this matcher's settings.
	 * @param path the path to tokenize
	 * @return the tokenized path parts
	 */
	protected String[] tokenizePath(String path) {
		//按分割符分词
		return StringUtils.tokenizeToStringArray(path, this.pathSeparator, this.trimTokens, true);
	}

	/**
	 * Test whether or not a string matches against a pattern.
	 * @param pattern the pattern to match against (never {@code null})
	 * @param str the String which must be matched against the pattern (never {@code null})
	 * @return {@code true} if the string matches against the pattern, or {@code false} otherwise
	 */
	private boolean matchStrings(String pattern, String str,
			@Nullable Map<String, String> uriTemplateVariables) {

		return getStringMatcher(pattern).matchStrings(str, uriTemplateVariables);
	}

	/**
	 * Build or retrieve an {@link AntPathStringMatcher} for the given pattern.
	 * <p>The default implementation checks this AntPathMatcher's internal cache
	 * (see {@link #setCachePatterns}), creating a new AntPathStringMatcher instance
	 * if no cached copy is found.
	 * <p>When encountering too many patterns to cache at runtime (the threshold is 65536),
	 * it turns the default cache off, assuming that arbitrary permutations of patterns
	 * are coming in, with little chance for encountering a recurring pattern.
	 * <p>This method may be overridden to implement a custom cache strategy.
	 * @param pattern the pattern to match against (never {@code null})
	 * @return a corresponding AntPathStringMatcher (never {@code null})
	 * @see #setCachePatterns
	 */
	protected AntPathStringMatcher getStringMatcher(String pattern) {
		AntPathStringMatcher matcher = null;
		Boolean cachePatterns = this.cachePatterns;
		if (cachePatterns == null || cachePatterns.booleanValue()) {
			matcher = this.stringMatcherCache.get(pattern);
		}
		if (matcher == null) {
			matcher = new AntPathStringMatcher(pattern, this.caseSensitive);
			if (cachePatterns == null && this.stringMatcherCache.size() >= CACHE_TURNOFF_THRESHOLD) {
				// Try to adapt to the runtime situation that we're encountering:
				// There are obviously too many different patterns coming in here...
				// So let's turn off the cache since the patterns are unlikely to be reoccurring.
				deactivatePatternCache();
				return matcher;
			}
			if (cachePatterns == null || cachePatterns.booleanValue()) {
				this.stringMatcherCache.put(pattern, matcher);
			}
		}
		return matcher;
	}

	/**
	 * Given a pattern and a full path, determine the pattern-mapped part. <p>For example: <ul>
	 * <li>'{@code /docs/cvs/commit.html}' and '{@code /docs/cvs/commit.html} -> ''</li>
	 * <li>'{@code /docs/*}' and '{@code /docs/cvs/commit} -> '{@code cvs/commit}'</li>
	 * <li>'{@code /docs/cvs/*.html}' and '{@code /docs/cvs/commit.html} -> '{@code commit.html}'</li>
	 * <li>'{@code /docs/**}' and '{@code /docs/cvs/commit} -> '{@code cvs/commit}'</li>
	 * <li>'{@code /docs/**\/*.html}' and '{@code /docs/cvs/commit.html} -> '{@code cvs/commit.html}'</li>
	 * <li>'{@code /*.html}' and '{@code /docs/cvs/commit.html} -> '{@code docs/cvs/commit.html}'</li>
	 * <li>'{@code *.html}' and '{@code /docs/cvs/commit.html} -> '{@code /docs/cvs/commit.html}'</li>
	 * <li>'{@code *}' and '{@code /docs/cvs/commit.html} -> '{@code /docs/cvs/commit.html}'</li> </ul>
	 * <p>Assumes that {@link #match} returns {@code true} for '{@code pattern}' and '{@code path}', but
	 * does <strong>not</strong> enforce this.
	 */
	@Override
	public String extractPathWithinPattern(String pattern, String path) {
		String[] patternParts = StringUtils.tokenizeToStringArray(pattern, this.pathSeparator, this.trimTokens, true);
		String[] pathParts = StringUtils.tokenizeToStringArray(path, this.pathSeparator, this.trimTokens, true);
		StringBuilder builder = new StringBuilder();
		boolean pathStarted = false;

		for (int segment = 0; segment < patternParts.length; segment++) {
			String patternPart = patternParts[segment];
			if (patternPart.indexOf('*') > -1 || patternPart.indexOf('?') > -1) {
				for (; segment < pathParts.length; segment++) {
					if (pathStarted || (segment == 0 && !pattern.startsWith(this.pathSeparator))) {
						builder.append(this.pathSeparator);
					}
					builder.append(pathParts[segment]);
					pathStarted = true;
				}
			}
		}

		return builder.toString();
	}

	@Override
	public Map<String, String> extractUriTemplateVariables(String pattern, String path) {
		Map<String, String> variables = new LinkedHashMap<>();
		boolean result = doMatch(pattern, path, true, variables);
		if (!result) {
			throw new IllegalStateException("Pattern \"" + pattern + "\" is not a match for \"" + path + "\"");
		}
		return variables;
	}

	/**
	 * Combine two patterns into a new pattern.
	 * <p>This implementation simply concatenates the two patterns, unless
	 * the first pattern contains a file extension match (e.g., {@code *.html}).
	 * In that case, the second pattern will be merged into the first. Otherwise,
	 * an {@code IllegalArgumentException} will be thrown.
	 * <h3>Examples</h3>
	 * <table border="1">
	 * <tr><th>Pattern 1</th><th>Pattern 2</th><th>Result</th></tr>
	 * <tr><td>{@code null}</td><td>{@code null}</td><td>&nbsp;</td></tr>
	 * <tr><td>/hotels</td><td>{@code null}</td><td>/hotels</td></tr>
	 * <tr><td>{@code null}</td><td>/hotels</td><td>/hotels</td></tr>
	 * <tr><td>/hotels</td><td>/bookings</td><td>/hotels/bookings</td></tr>
	 * <tr><td>/hotels</td><td>bookings</td><td>/hotels/bookings</td></tr>
	 * <tr><td>/hotels/*</td><td>/bookings</td><td>/hotels/bookings</td></tr>
	 * <tr><td>/hotels/&#42;&#42;</td><td>/bookings</td><td>/hotels/&#42;&#42;/bookings</td></tr>
	 * <tr><td>/hotels</td><td>{hotel}</td><td>/hotels/{hotel}</td></tr>
	 * <tr><td>/hotels/*</td><td>{hotel}</td><td>/hotels/{hotel}</td></tr>
	 * <tr><td>/hotels/&#42;&#42;</td><td>{hotel}</td><td>/hotels/&#42;&#42;/{hotel}</td></tr>
	 * <tr><td>/*.html</td><td>/hotels.html</td><td>/hotels.html</td></tr>
	 * <tr><td>/*.html</td><td>/hotels</td><td>/hotels.html</td></tr>
	 * <tr><td>/*.html</td><td>/*.txt</td><td>{@code IllegalArgumentException}</td></tr>
	 * </table>
	 * @param pattern1 the first pattern
	 * @param pattern2 the second pattern
	 * @return the combination of the two patterns
	 * @throws IllegalArgumentException if the two patterns cannot be combined
	 */
	@Override
	public String combine(String pattern1, String pattern2) {
		if (!StringUtils.hasText(pattern1) && !StringUtils.hasText(pattern2)) {
			return "";
		}
		if (!StringUtils.hasText(pattern1)) {
			return pattern2;
		}
		if (!StringUtils.hasText(pattern2)) {
			return pattern1;
		}

		boolean pattern1ContainsUriVar = (pattern1.indexOf('{') != -1);
		if (!pattern1.equals(pattern2) && !pattern1ContainsUriVar && match(pattern1, pattern2)) {
			// /* + /hotel -> /hotel ; "/*.*" + "/*.html" -> /*.html
			// However /user + /user -> /usr/user ; /{foo} + /bar -> /{foo}/bar
			return pattern2;
		}

		// /hotels/* + /booking -> /hotels/booking
		// /hotels/* + booking -> /hotels/booking
		if (pattern1.endsWith(this.pathSeparatorPatternCache.getEndsOnWildCard())) {
			return concat(pattern1.substring(0, pattern1.length() - 2), pattern2);
		}

		// /hotels/** + /booking -> /hotels/**/booking
		// /hotels/** + booking -> /hotels/**/booking
		if (pattern1.endsWith(this.pathSeparatorPatternCache.getEndsOnDoubleWildCard())) {
			return concat(pattern1, pattern2);
		}

		int starDotPos1 = pattern1.indexOf("*.");
		if (pattern1ContainsUriVar || starDotPos1 == -1 || this.pathSeparator.equals(".")) {
			// simply concatenate the two patterns
			return concat(pattern1, pattern2);
		}

		String ext1 = pattern1.substring(starDotPos1 + 1);
		int dotPos2 = pattern2.indexOf('.');
		String file2 = (dotPos2 == -1 ? pattern2 : pattern2.substring(0, dotPos2));
		String ext2 = (dotPos2 == -1 ? "" : pattern2.substring(dotPos2));
		boolean ext1All = (ext1.equals(".*") || ext1.isEmpty());
		boolean ext2All = (ext2.equals(".*") || ext2.isEmpty());
		if (!ext1All && !ext2All) {
			throw new IllegalArgumentException("Cannot combine patterns: " + pattern1 + " vs " + pattern2);
		}
		String ext = (ext1All ? ext2 : ext1);
		return file2 + ext;
	}

	private String concat(String path1, String path2) {
		boolean path1EndsWithSeparator = path1.endsWith(this.pathSeparator);
		boolean path2StartsWithSeparator = path2.startsWith(this.pathSeparator);

		if (path1EndsWithSeparator && path2StartsWithSeparator) {
			return path1 + path2.substring(1);
		}
		else if (path1EndsWithSeparator || path2StartsWithSeparator) {
			return path1 + path2;
		}
		else {
			return path1 + this.pathSeparator + path2;
		}
	}

	/**
	 * Given a full path, returns a {@link Comparator} suitable for sorting patterns in order of
	 * explicitness.
	 * <p>This{@code Comparator} will {@linkplain java.util.List#sort(Comparator) sort}
	 * a list so that more specific patterns (without uri templates or wild cards) come before
	 * generic patterns. So given a list with the following patterns:
	 * <ol>
	 * <li>{@code /hotels/new}</li>
	 * <li>{@code /hotels/{hotel}}</li> <li>{@code /hotels/*}</li>
	 * </ol>
	 * the returned comparator will sort this list so that the order will be as indicated.
	 * <p>The full path given as parameter is used to test for exact matches. So when the given path
	 * is {@code /hotels/2}, the pattern {@code /hotels/2} will be sorted before {@code /hotels/1}.
	 * @param path the full path to use for comparison
	 * @return a comparator capable of sorting patterns in order of explicitness
	 */
	@Override
	public Comparator<String> getPatternComparator(String path) {
		return new AntPatternComparator(path);
	}


	/**
	 * Tests whether or not a string matches against a pattern via a {@link Pattern}.
	 * <p>The pattern may contain special characters: '*' means zero or more characters; '?' means one and
	 * only one character; '{' and '}' indicate a URI template pattern. For example <tt>/users/{user}</tt>.
	 */
	protected static class AntPathStringMatcher {

		private static final Pattern GLOB_PATTERN = Pattern.compile("\\?|\\*|\\{((?:\\{[^/]+?\\}|[^/{}]|\\\\[{}])+?)\\}");

		private static final String DEFAULT_VARIABLE_PATTERN = "(.*)";

		private final Pattern pattern;

		private final List<String> variableNames = new LinkedList<>();

		public AntPathStringMatcher(String pattern) {
			this(pattern, true);
		}

		public AntPathStringMatcher(String pattern, boolean caseSensitive) {
			StringBuilder patternBuilder = new StringBuilder();
			Matcher matcher = GLOB_PATTERN.matcher(pattern);
			int end = 0;
			while (matcher.find()) {
				patternBuilder.append(quote(pattern, end, matcher.start()));
				String match = matcher.group();
				if ("?".equals(match)) {
					patternBuilder.append('.');
				}
				else if ("*".equals(match)) {
					patternBuilder.append(".*");
				}
				else if (match.startsWith("{") && match.endsWith("}")) {
					int colonIdx = match.indexOf(':');
					if (colonIdx == -1) {
						patternBuilder.append(DEFAULT_VARIABLE_PATTERN);
						this.variableNames.add(matcher.group(1));
					}
					else {
						String variablePattern = match.substring(colonIdx + 1, match.length() - 1);
						patternBuilder.append('(');
						patternBuilder.append(variablePattern);
						patternBuilder.append(')');
						String variableName = match.substring(1, colonIdx);
						this.variableNames.add(variableName);
					}
				}
				end = matcher.end();
			}
			patternBuilder.append(quote(pattern, end, pattern.length()));
			this.pattern = (caseSensitive ? Pattern.compile(patternBuilder.toString()) :
					Pattern.compile(patternBuilder.toString(), Pattern.CASE_INSENSITIVE));
		}

		private String quote(String s, int start, int end) {
			if (start == end) {
				return "";
			}
			return Pattern.quote(s.substring(start, end));
		}

		/**
		 * Main entry point.
		 * @return {@code true} if the string matches against the pattern, or {@code false} otherwise.
		 */
		public boolean matchStrings(String str, @Nullable Map<String, String> uriTemplateVariables) {
			Matcher matcher = this.pattern.matcher(str);
			if (matcher.matches()) {
				if (uriTemplateVariables != null) {
					// SPR-8455
					if (this.variableNames.size() != matcher.groupCount()) {
						throw new IllegalArgumentException("The number of capturing groups in the pattern segment " +
								this.pattern + " does not match the number of URI template variables it defines, " +
								"which can occur if capturing groups are used in a URI template regex. " +
								"Use non-capturing groups instead.");
					}
					for (int i = 1; i <= matcher.groupCount(); i++) {
						String name = this.variableNames.get(i - 1);
						String value = matcher.group(i);
						uriTemplateVariables.put(name, value);
					}
				}
				return true;
			}
			else {
				return false;
			}
		}
	}


	/**
	 * The default {@link Comparator} implementation returned by
	 * {@link #getPatternComparator(String)}.
	 * <p>In order, the most "generic" pattern is determined by the following:
	 * <ul>
	 * <li>if it's null or a capture all pattern (i.e. it is equal to "/**")</li>
	 * <li>if the other pattern is an actual match</li>
	 * <li>if it's a catch-all pattern (i.e. it ends with "**"</li>
	 * <li>if it's got more "*" than the other pattern</li>
	 * <li>if it's got more "{foo}" than the other pattern</li>
	 * <li>if it's shorter than the other pattern</li>
	 * </ul>
	 */
	protected static class AntPatternComparator implements Comparator<String> {

		private final String path;

		public AntPatternComparator(String path) {
			this.path = path;
		}

		/**
		 * Compare two patterns to determine which should match first, i.e. which
		 * is the most specific regarding the current path.
		 * @return a negative integer, zero, or a positive integer as pattern1 is
		 * more specific, equally specific, or less specific than pattern2.
		 */
		@Override
		public int compare(String pattern1, String pattern2) {
			PatternInfo info1 = new PatternInfo(pattern1);
			PatternInfo info2 = new PatternInfo(pattern2);

			if (info1.isLeastSpecific() && info2.isLeastSpecific()) {
				return 0;
			}
			else if (info1.isLeastSpecific()) {
				return 1;
			}
			else if (info2.isLeastSpecific()) {
				return -1;
			}

			boolean pattern1EqualsPath = pattern1.equals(this.path);
			boolean pattern2EqualsPath = pattern2.equals(this.path);
			if (pattern1EqualsPath && pattern2EqualsPath) {
				return 0;
			}
			else if (pattern1EqualsPath) {
				return -1;
			}
			else if (pattern2EqualsPath) {
				return 1;
			}

			if (info1.isPrefixPattern() && info2.isPrefixPattern()) {
				return info2.getLength() - info1.getLength();
			}
			else if (info1.isPrefixPattern() && info2.getDoubleWildcards() == 0) {
				return 1;
			}
			else if (info2.isPrefixPattern() && info1.getDoubleWildcards() == 0) {
				return -1;
			}

			if (info1.getTotalCount() != info2.getTotalCount()) {
				return info1.getTotalCount() - info2.getTotalCount();
			}

			if (info1.getLength() != info2.getLength()) {
				return info2.getLength() - info1.getLength();
			}

			if (info1.getSingleWildcards() < info2.getSingleWildcards()) {
				return -1;
			}
			else if (info2.getSingleWildcards() < info1.getSingleWildcards()) {
				return 1;
			}

			if (info1.getUriVars() < info2.getUriVars()) {
				return -1;
			}
			else if (info2.getUriVars() < info1.getUriVars()) {
				return 1;
			}

			return 0;
		}


		/**
		 * Value class that holds information about the pattern, e.g. number of
		 * occurrences of "*", "**", and "{" pattern elements.
		 */
		private static class PatternInfo {

			@Nullable
			private final String pattern;

			private int uriVars;

			private int singleWildcards;

			private int doubleWildcards;

			private boolean catchAllPattern;

			private boolean prefixPattern;

			@Nullable
			private Integer length;

			public PatternInfo(@Nullable String pattern) {
				this.pattern = pattern;
				if (this.pattern != null) {
					initCounters();
					this.catchAllPattern = this.pattern.equals("/**");
					this.prefixPattern = !this.catchAllPattern && this.pattern.endsWith("/**");
				}
				if (this.uriVars == 0) {
					this.length = (this.pattern != null ? this.pattern.length() : 0);
				}
			}

			protected void initCounters() {
				int pos = 0;
				if (this.pattern != null) {
					while (pos < this.pattern.length()) {
						if (this.pattern.charAt(pos) == '{') {
							this.uriVars++;
							pos++;
						}
						else if (this.pattern.charAt(pos) == '*') {
							if (pos + 1 < this.pattern.length() && this.pattern.charAt(pos + 1) == '*') {
								this.doubleWildcards++;
								pos += 2;
							}
							else if (pos > 0 && !this.pattern.substring(pos - 1).equals(".*")) {
								this.singleWildcards++;
								pos++;
							}
							else {
								pos++;
							}
						}
						else {
							pos++;
						}
					}
				}
			}

			public int getUriVars() {
				return this.uriVars;
			}

			public int getSingleWildcards() {
				return this.singleWildcards;
			}

			public int getDoubleWildcards() {
				return this.doubleWildcards;
			}

			public boolean isLeastSpecific() {
				return (this.pattern == null || this.catchAllPattern);
			}

			public boolean isPrefixPattern() {
				return this.prefixPattern;
			}

			public int getTotalCount() {
				return this.uriVars + this.singleWildcards + (2 * this.doubleWildcards);
			}

			/**
			 * Returns the length of the given pattern, where template variables are considered to be 1 long.
			 */
			public int getLength() {
				if (this.length == null) {
					this.length = (this.pattern != null ?
							VARIABLE_PATTERN.matcher(this.pattern).replaceAll("#").length() : 0);
				}
				return this.length;
			}
		}
	}


	/**
	 * A simple cache for patterns that depend on the configured path separator.
	 */
	private static class PathSeparatorPatternCache {

		private final String endsOnWildCard;

		private final String endsOnDoubleWildCard;

		public PathSeparatorPatternCache(String pathSeparator) {
			this.endsOnWildCard = pathSeparator + "*";
			this.endsOnDoubleWildCard = pathSeparator + "**";
		}

		public String getEndsOnWildCard() {
			return this.endsOnWildCard;
		}

		public String getEndsOnDoubleWildCard() {
			return this.endsOnDoubleWildCard;
		}
	}

}
