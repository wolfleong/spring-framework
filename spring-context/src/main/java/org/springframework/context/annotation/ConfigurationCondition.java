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

package org.springframework.context.annotation;

/**
 * 这个子接口是一种特殊的条件接口，多了一个 getConfigurationPhase 方法，也就是条件注解的生效阶段。只有在 ConfigurationPhase 中定义的两种阶段下才会生效
 * 与@Configuration一起使用时提供更精细控制的条件，允许某些Condition在匹配时根据配置阶段进行调整
 * A {@link Condition} that offers more fine-grained control when used with
 * {@code @Configuration}. Allows certain {@link Condition Conditions} to adapt when they match
 * based on the configuration phase. For example, a condition that checks if a bean
 * has already been registered might choose to only be evaluated during the
 * {@link ConfigurationPhase#REGISTER_BEAN REGISTER_BEAN} {@link ConfigurationPhase}.
 *
 * @author Phillip Webb
 * @since 4.0
 * @see Configuration
 */
public interface ConfigurationCondition extends Condition {

	/**
	 * 生效阶段
	 * Return the {@link ConfigurationPhase} in which the condition should be evaluated.
	 */
	ConfigurationPhase getConfigurationPhase();


	/**
	 * 可以判断Condition的不同配置阶段
	 * The various configuration phases where the condition could be evaluated.
	 */
	enum ConfigurationPhase {

		/**
		 * 在 @Configuration 注解的类解析的阶段判断 Condition 如果 Condition 不匹配，则 @Configuration 注解的类不会加载
		 * The {@link Condition} should be evaluated as a {@code @Configuration}
		 * class is being parsed.
		 * <p>If the condition does not match at this point, the {@code @Configuration}
		 * class will not be added.
		 */
		PARSE_CONFIGURATION,

		/**
		 * 在 @Configuration 注解的类实例化为 bean 的阶段判断Condition, 无论 Condition 是否匹配，@Configuration 注解的类都会加载，且类加载先于Condition判断
		 * The {@link Condition} should be evaluated when adding a regular
		 * (non {@code @Configuration}) bean. The condition will not prevent
		 * {@code @Configuration} classes from being added.
		 * <p>At the time that the condition is evaluated, all {@code @Configuration}s
		 * will have been parsed.
		 */
		REGISTER_BEAN
	}

}
