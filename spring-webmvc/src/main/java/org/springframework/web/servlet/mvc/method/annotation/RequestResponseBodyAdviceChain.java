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

package org.springframework.web.servlet.mvc.method.annotation;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.springframework.core.MethodParameter;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.lang.Nullable;
import org.springframework.util.CollectionUtils;
import org.springframework.web.method.ControllerAdviceBean;

/**
 * Invokes {@link RequestBodyAdvice} and {@link ResponseBodyAdvice} where each
 * instance may be (and is most likely) wrapped with
 * {@link org.springframework.web.method.ControllerAdviceBean ControllerAdviceBean}.
 *
 * @author Rossen Stoyanchev
 * @since 4.2
 */
class RequestResponseBodyAdviceChain implements RequestBodyAdvice, ResponseBodyAdvice<Object> {

	private final List<Object> requestBodyAdvice = new ArrayList<>(4);

	private final List<Object> responseBodyAdvice = new ArrayList<>(4);


	/**
	 * Create an instance from a list of objects that are either of type
	 * {@code ControllerAdviceBean} or {@code RequestBodyAdvice}.
	 */
	public RequestResponseBodyAdviceChain(@Nullable List<Object> requestResponseBodyAdvice) {
		//获取所有 RequestBodyAdvice 类型的 ControllerAdviceBean
		this.requestBodyAdvice.addAll(getAdviceByType(requestResponseBodyAdvice, RequestBodyAdvice.class));
		//获取所有 ResponseBodyAdvice 类型 ControllerAdviceBean
		this.responseBodyAdvice.addAll(getAdviceByType(requestResponseBodyAdvice, ResponseBodyAdvice.class));
	}

	@SuppressWarnings("unchecked")
	static <T> List<T> getAdviceByType(@Nullable List<Object> requestResponseBodyAdvice, Class<T> adviceType) {
		if (requestResponseBodyAdvice != null) {
			List<T> result = new ArrayList<>();
			//遍历
			for (Object advice : requestResponseBodyAdvice) {
				//获取 BeanType
				Class<?> beanType = (advice instanceof ControllerAdviceBean ?
						((ControllerAdviceBean) advice).getBeanType() : advice.getClass());
				//如果 beanType 是 给定类型, 则添加的结果集中
				if (beanType != null && adviceType.isAssignableFrom(beanType)) {
					result.add((T) advice);
				}
			}
			//返回结果
			return result;
		}
		return Collections.emptyList();
	}


	@Override
	public boolean supports(MethodParameter param, Type type, Class<? extends HttpMessageConverter<?>> converterType) {
		throw new UnsupportedOperationException("Not implemented");
	}

	@Override
	public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
		throw new UnsupportedOperationException("Not implemented");
	}

	@Override
	public HttpInputMessage beforeBodyRead(HttpInputMessage request, MethodParameter parameter,
			Type targetType, Class<? extends HttpMessageConverter<?>> converterType) throws IOException {

		//遍历匹配的 ControllerAdvice 实例
		for (RequestBodyAdvice advice : getMatchingAdvice(parameter, RequestBodyAdvice.class)) {
			//判断是否支持
			if (advice.supports(parameter, targetType, converterType)) {
				//进行处理
				request = advice.beforeBodyRead(request, parameter, targetType, converterType);
			}
		}
		return request;
	}

	@Override
	public Object afterBodyRead(Object body, HttpInputMessage inputMessage, MethodParameter parameter,
			Type targetType, Class<? extends HttpMessageConverter<?>> converterType) {

		//遍历匹配的 ControllerAdvice 实例, 并判断是否支持处理, 支持则回调
		for (RequestBodyAdvice advice : getMatchingAdvice(parameter, RequestBodyAdvice.class)) {
			if (advice.supports(parameter, targetType, converterType)) {
				body = advice.afterBodyRead(body, inputMessage, parameter, targetType, converterType);
			}
		}
		return body;
	}

	@Override
	@Nullable
	public Object beforeBodyWrite(@Nullable Object body, MethodParameter returnType, MediaType contentType,
			Class<? extends HttpMessageConverter<?>> converterType,
			ServerHttpRequest request, ServerHttpResponse response) {

		return processBody(body, returnType, contentType, converterType, request, response);
	}

	@Override
	@Nullable
	public Object handleEmptyBody(@Nullable Object body, HttpInputMessage inputMessage, MethodParameter parameter,
			Type targetType, Class<? extends HttpMessageConverter<?>> converterType) {

		for (RequestBodyAdvice advice : getMatchingAdvice(parameter, RequestBodyAdvice.class)) {
			if (advice.supports(parameter, targetType, converterType)) {
				body = advice.handleEmptyBody(body, inputMessage, parameter, targetType, converterType);
			}
		}
		return body;
	}


	@SuppressWarnings("unchecked")
	@Nullable
	private <T> Object processBody(@Nullable Object body, MethodParameter returnType, MediaType contentType,
			Class<? extends HttpMessageConverter<?>> converterType,
			ServerHttpRequest request, ServerHttpResponse response) {

		//遍历匹配的 ControllerAdvice 实例, 并判断是否支持处理, 支持则回调
		for (ResponseBodyAdvice<?> advice : getMatchingAdvice(returnType, ResponseBodyAdvice.class)) {
			if (advice.supports(returnType, converterType)) {
				body = ((ResponseBodyAdvice<T>) advice).beforeBodyWrite((T) body, returnType,
						contentType, converterType, request, response);
			}
		}
		return body;
	}

	/**
	 * 获取匹配的 Advice 处理器
	 */
	@SuppressWarnings("unchecked")
	private <A> List<A> getMatchingAdvice(MethodParameter parameter, Class<? extends A> adviceType) {
		//获取指定类型的处理器
		List<Object> availableAdvice = getAdvice(adviceType);
		//如果是空, 则返回空
		if (CollectionUtils.isEmpty(availableAdvice)) {
			return Collections.emptyList();
		}
		List<A> result = new ArrayList<>(availableAdvice.size());
		//遍历
		for (Object advice : availableAdvice) {
			//如果是 ControllerAdviceBean 类型
			if (advice instanceof ControllerAdviceBean) {
				//强转
				ControllerAdviceBean adviceBean = (ControllerAdviceBean) advice;
				//如果类型不匹配, 则不处理
				if (!adviceBean.isApplicableToBeanType(parameter.getContainingClass())) {
					continue;
				}
				//获取处理器
				advice = adviceBean.resolveBean();
			}
			//如果处理器是对应类型(RequestBodyAdvice, ResponseBodyAdvice)
			if (adviceType.isAssignableFrom(advice.getClass())) {
				result.add((A) advice);
			}
		}
		//返回
		return result;
	}

	/**
	 * 获取对应类型的 ControllerAdviceBean 列表
	 */
	private List<Object> getAdvice(Class<?> adviceType) {
		if (RequestBodyAdvice.class == adviceType) {
			return this.requestBodyAdvice;
		}
		else if (ResponseBodyAdvice.class == adviceType) {
			return this.responseBodyAdvice;
		}
		else {
			throw new IllegalArgumentException("Unexpected adviceType: " + adviceType);
		}
	}

}
