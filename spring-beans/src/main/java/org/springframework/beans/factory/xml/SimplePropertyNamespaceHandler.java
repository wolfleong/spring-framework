/*
 * Copyright 2002-2012 the original author or authors.
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

import org.w3c.dom.Attr;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import org.springframework.beans.MutablePropertyValues;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanDefinitionHolder;
import org.springframework.beans.factory.config.RuntimeBeanReference;
import org.springframework.core.Conventions;
import org.springframework.lang.Nullable;

/**
 * 简单属性解析器, 它将特定属性直接映射到bean属性, 如:
 * 	 <bean id = "rob" class = "..TestBean" p:name="Rob" p:spouse-ref="sally"/>
 * 	- 这里的p:name直接对应于类TestBean上的name属性。p:spouse-ref属性对应于spouse属性，将value所对应的bean注入到该属性中
 *
 * Simple {@code NamespaceHandler} implementation that maps custom attributes
 * directly through to bean properties. An important point to note is that this
 * {@code NamespaceHandler} does not have a corresponding schema since there
 * is no way to know in advance all possible attribute names.
 *
 * <p>An example of the usage of this {@code NamespaceHandler} is shown below:
 *
 * <pre class="code">
 * &lt;bean id=&quot;rob&quot; class=&quot;..TestBean&quot; p:name=&quot;Rob Harrop&quot; p:spouse-ref=&quot;sally&quot;/&gt;</pre>
 *
 * Here the '{@code p:name}' corresponds directly to the '{@code name}'
 * property on class '{@code TestBean}'. The '{@code p:spouse-ref}'
 * attributes corresponds to the '{@code spouse}' property and, rather
 * than being the concrete value, it contains the name of the bean that will
 * be injected into that property.
 *
 * @author Rob Harrop
 * @author Juergen Hoeller
 * @since 2.0
 */
public class SimplePropertyNamespaceHandler implements NamespaceHandler {

	private static final String REF_SUFFIX = "-ref";


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
		//判断是不是 Attr 类型, 这里只处理标签属性
		if (node instanceof Attr) {
			//强转
			Attr attr = (Attr) node;
			//获取属性名称
			String propertyName = parserContext.getDelegate().getLocalName(attr);
			//获取属性值
			String propertyValue = attr.getValue();
			//从当前bean定义取出属性值集合
			MutablePropertyValues pvs = definition.getBeanDefinition().getPropertyValues();
			//如果属性已经存在, 则报错
			if (pvs.contains(propertyName)) {
				parserContext.getReaderContext().error("Property '" + propertyName + "' is already defined using " +
						"both <property> and inline syntax. Only one approach may be used per property.", attr);
			}
			//如果属性名以 _ref 结尾, 则表示该属性值引用一个 bean , _ref 前面是 bean 名称
			if (propertyName.endsWith(REF_SUFFIX)) {
				//获取属性名
				propertyName = propertyName.substring(0, propertyName.length() - REF_SUFFIX.length());
				//创建 RuntimeBeanReference , 并添加属性
				pvs.add(Conventions.attributeNameToPropertyName(propertyName), new RuntimeBeanReference(propertyValue));
			}
			else {
				//直接添加属性值和名称
				pvs.add(Conventions.attributeNameToPropertyName(propertyName), propertyValue);
			}
		}
		//返回
		return definition;
	}

}
