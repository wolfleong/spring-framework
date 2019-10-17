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

package org.springframework.beans.factory.xml;

import java.util.Collection;

import org.w3c.dom.Attr;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanDefinitionHolder;
import org.springframework.beans.factory.config.ConstructorArgumentValues;
import org.springframework.beans.factory.config.ConstructorArgumentValues.ValueHolder;
import org.springframework.beans.factory.config.RuntimeBeanReference;
import org.springframework.core.Conventions;
import org.springframework.lang.Nullable;
import org.springframework.util.StringUtils;

/**
 * 简单构造函数解析器, 将自定义属性映射到构造函数参数。如:
 *  <bean id="author" class="..TestBean" c:name="Enescu" c:work-ref="compositions" c:_0="value" c:_1-ref="beanName"/>
 *
 * Simple {@code NamespaceHandler} implementation that maps custom
 * attributes directly through to bean properties. An important point to note is
 * that this {@code NamespaceHandler} does not have a corresponding schema
 * since there is no way to know in advance all possible attribute names.
 *
 * <p>An example of the usage of this {@code NamespaceHandler} is shown below:
 *
 * <pre class="code">
 * &lt;bean id=&quot;author&quot; class=&quot;..TestBean&quot; c:name=&quot;Enescu&quot; c:work-ref=&quot;compositions&quot;/&gt;
 * </pre>
 *
 * Here the '{@code c:name}' corresponds directly to the '{@code name}
 * ' argument declared on the constructor of class '{@code TestBean}'. The
 * '{@code c:work-ref}' attributes corresponds to the '{@code work}'
 * argument and, rather than being the concrete value, it contains the name of
 * the bean that will be considered as a parameter.
 *
 * <b>Note</b>: This implementation supports only named parameters - there is no
 * support for indexes or types. Further more, the names are used as hints by
 * the container which, by default, does type introspection.
 *
 * @author Costin Leau
 * @since 3.1
 * @see SimplePropertyNamespaceHandler
 */
public class SimpleConstructorNamespaceHandler implements NamespaceHandler {

	private static final String REF_SUFFIX = "-ref";

	private static final String DELIMITER_PREFIX = "_";


	@Override
	public void init() {
	}

	@Override
	@Nullable
	public BeanDefinition parse(Element element, ParserContext parserContext) {
		parserContext.getReaderContext().error(
				"Class [" + getClass().getName() + "] does not support custom elements.", element);
		return null;
	}

	@Override
	public BeanDefinitionHolder decorate(Node node, BeanDefinitionHolder definition, ParserContext parserContext) {
		////只解析标签属性
		if (node instanceof Attr) {
			//强转
			Attr attr = (Attr) node;
			//获取参数名
			String argName = StringUtils.trimWhitespace(parserContext.getDelegate().getLocalName(attr));
			//获取参数值
			String argValue = StringUtils.trimWhitespace(attr.getValue());

			//获取构造参数列表
			ConstructorArgumentValues cvs = definition.getBeanDefinition().getConstructorArgumentValues();
			//是否有 bean 引用
			boolean ref = false;

			//如果有 -ref 结尾 , 则表示有引用
			// handle -ref arguments
			if (argName.endsWith(REF_SUFFIX)) {
				//设置有引用
				ref = true;
				//获取 -ref 前的参数名
				argName = argName.substring(0, argName.length() - REF_SUFFIX.length());
			}

			//创建 ValueHolder
			ValueHolder valueHolder = new ValueHolder(ref ? new RuntimeBeanReference(argValue) : argValue);
			//设置 source
			valueHolder.setSource(parserContext.getReaderContext().extractSource(attr));

			//如果有下划线开头
			// handle "escaped"/"_" arguments
			if (argName.startsWith(DELIMITER_PREFIX)) {
				//获取下划线之后的内容
				String arg = argName.substring(1).trim();

				//如果下划线之后为空
				// fast default check
				if (!StringUtils.hasText(arg)) {
					//如果参数名为空，那么添加常规参数值
					cvs.addGenericArgumentValue(valueHolder);
				}
				//下划线后面的内容不为空
				// assume an index otherwise
				else {
					int index = -1;
					try {
						//转成索引
						index = Integer.parseInt(arg);
					}
					catch (NumberFormatException ex) {
						//转数字不成功, 报错
						parserContext.getReaderContext().error(
								"Constructor argument '" + argName + "' specifies an invalid integer", attr);
					}
					//如果索引小于 0, 则
					if (index < 0) {
						parserContext.getReaderContext().error(
								"Constructor argument '" + argName + "' specifies a negative index", attr);
					}

					//判断索引参数值是否存在, 存在则报错
					if (cvs.hasIndexedArgumentValue(index)) {
						parserContext.getReaderContext().error(
								"Constructor argument '" + argName + "' with index "+ index+" already defined using <constructor-arg>." +
								" Only one approach may be used per argument.", attr);
					}

					//添加索引值
					cvs.addIndexedArgumentValue(index, valueHolder);
				}
			}
			//如果没有索引值, 则表示有构造参数名称
			// no escaping -> ctr name
			else {
				//处理名称为驼峰
				String name = Conventions.attributeNameToPropertyName(argName);
				//如果参数名已经存在, 则报错
				if (containsArgWithName(name, cvs)) {
					parserContext.getReaderContext().error(
							"Constructor argument '" + argName + "' already defined using <constructor-arg>." +
							" Only one approach may be used per argument.", attr);
				}
				//设置参数的名称
				valueHolder.setName(Conventions.attributeNameToPropertyName(argName));
				//添加到通用构造参数中
				cvs.addGenericArgumentValue(valueHolder);
			}
		}
		return definition;
	}

	/**
	 * 检查参数名称是否存在
	 */
	private boolean containsArgWithName(String name, ConstructorArgumentValues cvs) {
		return (checkName(name, cvs.getGenericArgumentValues()) ||
				checkName(name, cvs.getIndexedArgumentValues().values()));
	}

	private boolean checkName(String name, Collection<ValueHolder> values) {
		//遍历 values
		for (ValueHolder holder : values) {
			//如果有名称相同
			if (name.equals(holder.getName())) {
				//返回 true
				return true;
			}
		}
		//返回 false
		return false;
	}

}
