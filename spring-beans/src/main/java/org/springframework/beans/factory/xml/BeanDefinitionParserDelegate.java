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

package org.springframework.beans.factory.xml;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import org.springframework.beans.BeanMetadataAttribute;
import org.springframework.beans.BeanMetadataAttributeAccessor;
import org.springframework.beans.PropertyValue;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanDefinitionHolder;
import org.springframework.beans.factory.config.ConstructorArgumentValues;
import org.springframework.beans.factory.config.RuntimeBeanNameReference;
import org.springframework.beans.factory.config.RuntimeBeanReference;
import org.springframework.beans.factory.config.TypedStringValue;
import org.springframework.beans.factory.parsing.BeanEntry;
import org.springframework.beans.factory.parsing.ConstructorArgumentEntry;
import org.springframework.beans.factory.parsing.ParseState;
import org.springframework.beans.factory.parsing.PropertyEntry;
import org.springframework.beans.factory.parsing.QualifierEntry;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.AutowireCandidateQualifier;
import org.springframework.beans.factory.support.BeanDefinitionDefaults;
import org.springframework.beans.factory.support.BeanDefinitionReaderUtils;
import org.springframework.beans.factory.support.LookupOverride;
import org.springframework.beans.factory.support.ManagedArray;
import org.springframework.beans.factory.support.ManagedList;
import org.springframework.beans.factory.support.ManagedMap;
import org.springframework.beans.factory.support.ManagedProperties;
import org.springframework.beans.factory.support.ManagedSet;
import org.springframework.beans.factory.support.MethodOverrides;
import org.springframework.beans.factory.support.ReplaceOverride;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.CollectionUtils;
import org.springframework.util.ObjectUtils;
import org.springframework.util.PatternMatchUtils;
import org.springframework.util.StringUtils;
import org.springframework.util.xml.DomUtils;

/**
 * Stateful delegate class used to parse XML bean definitions.
 * Intended for use by both the main parser and any extension
 * {@link BeanDefinitionParser BeanDefinitionParsers} or
 * {@link BeanDefinitionDecorator BeanDefinitionDecorators}.
 *
 * @author Rob Harrop
 * @author Juergen Hoeller
 * @author Rod Johnson
 * @author Mark Fisher
 * @author Gary Russell
 * @since 2.0
 * @see ParserContext
 * @see DefaultBeanDefinitionDocumentReader
 */
public class BeanDefinitionParserDelegate {

	public static final String BEANS_NAMESPACE_URI = "http://www.springframework.org/schema/beans";

	public static final String MULTI_VALUE_ATTRIBUTE_DELIMITERS = ",; ";

	/**
	 * Value of a T/F attribute that represents true.
	 * Anything else represents false. Case seNsItive.
	 */
	public static final String TRUE_VALUE = "true";

	public static final String FALSE_VALUE = "false";

	public static final String DEFAULT_VALUE = "default";

	public static final String DESCRIPTION_ELEMENT = "description";

	public static final String AUTOWIRE_NO_VALUE = "no";

	public static final String AUTOWIRE_BY_NAME_VALUE = "byName";

	public static final String AUTOWIRE_BY_TYPE_VALUE = "byType";

	public static final String AUTOWIRE_CONSTRUCTOR_VALUE = "constructor";

	public static final String AUTOWIRE_AUTODETECT_VALUE = "autodetect";

	public static final String NAME_ATTRIBUTE = "name";

	public static final String BEAN_ELEMENT = "bean";

	public static final String META_ELEMENT = "meta";

	public static final String ID_ATTRIBUTE = "id";

	public static final String PARENT_ATTRIBUTE = "parent";

	public static final String CLASS_ATTRIBUTE = "class";

	public static final String ABSTRACT_ATTRIBUTE = "abstract";

	public static final String SCOPE_ATTRIBUTE = "scope";

	private static final String SINGLETON_ATTRIBUTE = "singleton";

	public static final String LAZY_INIT_ATTRIBUTE = "lazy-init";

	public static final String AUTOWIRE_ATTRIBUTE = "autowire";

	public static final String AUTOWIRE_CANDIDATE_ATTRIBUTE = "autowire-candidate";

	public static final String PRIMARY_ATTRIBUTE = "primary";

	public static final String DEPENDS_ON_ATTRIBUTE = "depends-on";

	public static final String INIT_METHOD_ATTRIBUTE = "init-method";

	public static final String DESTROY_METHOD_ATTRIBUTE = "destroy-method";

	public static final String FACTORY_METHOD_ATTRIBUTE = "factory-method";

	public static final String FACTORY_BEAN_ATTRIBUTE = "factory-bean";

	public static final String CONSTRUCTOR_ARG_ELEMENT = "constructor-arg";

	public static final String INDEX_ATTRIBUTE = "index";

	public static final String TYPE_ATTRIBUTE = "type";

	public static final String VALUE_TYPE_ATTRIBUTE = "value-type";

	public static final String KEY_TYPE_ATTRIBUTE = "key-type";

	public static final String PROPERTY_ELEMENT = "property";

	public static final String REF_ATTRIBUTE = "ref";

	public static final String VALUE_ATTRIBUTE = "value";

	public static final String LOOKUP_METHOD_ELEMENT = "lookup-method";

	public static final String REPLACED_METHOD_ELEMENT = "replaced-method";

	public static final String REPLACER_ATTRIBUTE = "replacer";

	public static final String ARG_TYPE_ELEMENT = "arg-type";

	public static final String ARG_TYPE_MATCH_ATTRIBUTE = "match";

	public static final String REF_ELEMENT = "ref";

	public static final String IDREF_ELEMENT = "idref";

	public static final String BEAN_REF_ATTRIBUTE = "bean";

	public static final String PARENT_REF_ATTRIBUTE = "parent";

	public static final String VALUE_ELEMENT = "value";

	public static final String NULL_ELEMENT = "null";

	public static final String ARRAY_ELEMENT = "array";

	public static final String LIST_ELEMENT = "list";

	public static final String SET_ELEMENT = "set";

	public static final String MAP_ELEMENT = "map";

	public static final String ENTRY_ELEMENT = "entry";

	public static final String KEY_ELEMENT = "key";

	public static final String KEY_ATTRIBUTE = "key";

	public static final String KEY_REF_ATTRIBUTE = "key-ref";

	public static final String VALUE_REF_ATTRIBUTE = "value-ref";

	public static final String PROPS_ELEMENT = "props";

	public static final String PROP_ELEMENT = "prop";

	public static final String MERGE_ATTRIBUTE = "merge";

	public static final String QUALIFIER_ELEMENT = "qualifier";

	public static final String QUALIFIER_ATTRIBUTE_ELEMENT = "attribute";

	public static final String DEFAULT_LAZY_INIT_ATTRIBUTE = "default-lazy-init";

	public static final String DEFAULT_MERGE_ATTRIBUTE = "default-merge";

	public static final String DEFAULT_AUTOWIRE_ATTRIBUTE = "default-autowire";

	public static final String DEFAULT_AUTOWIRE_CANDIDATES_ATTRIBUTE = "default-autowire-candidates";

	public static final String DEFAULT_INIT_METHOD_ATTRIBUTE = "default-init-method";

	public static final String DEFAULT_DESTROY_METHOD_ATTRIBUTE = "default-destroy-method";


	protected final Log logger = LogFactory.getLog(getClass());

	private final XmlReaderContext readerContext;

	private final DocumentDefaultsDefinition defaults = new DocumentDefaultsDefinition();

	private final ParseState parseState = new ParseState();

	/**
	 * 保存所有已经存在的 beanName
	 * Stores all used bean names so we can enforce uniqueness on a per
	 * beans-element basis. Duplicate bean ids/names may not exist within the
	 * same level of beans element nesting, but may be duplicated across levels.
	 */
	private final Set<String> usedNames = new HashSet<>();


	/**
	 * Create a new BeanDefinitionParserDelegate associated with the supplied
	 * {@link XmlReaderContext}.
	 */
	public BeanDefinitionParserDelegate(XmlReaderContext readerContext) {
		Assert.notNull(readerContext, "XmlReaderContext must not be null");
		this.readerContext = readerContext;
	}


	/**
	 * Get the {@link XmlReaderContext} associated with this helper instance.
	 */
	public final XmlReaderContext getReaderContext() {
		return this.readerContext;
	}

	/**
	 * Invoke the {@link org.springframework.beans.factory.parsing.SourceExtractor}
	 * to pull the source metadata from the supplied {@link Element}.
	 */
	@Nullable
	protected Object extractSource(Element ele) {
		return this.readerContext.extractSource(ele);
	}

	/**
	 * Report an error with the given message for the given source element.
	 */
	protected void error(String message, Node source) {
		this.readerContext.error(message, source, this.parseState.snapshot());
	}

	/**
	 * Report an error with the given message for the given source element.
	 */
	protected void error(String message, Element source) {
		this.readerContext.error(message, source, this.parseState.snapshot());
	}

	/**
	 * Report an error with the given message for the given source element.
	 */
	protected void error(String message, Element source, Throwable cause) {
		this.readerContext.error(message, source, this.parseState.snapshot(), cause);
	}


	/**
	 * Initialize the default settings assuming a {@code null} parent delegate.
	 */
	public void initDefaults(Element root) {
		initDefaults(root, null);
	}

	/**
	 * Initialize the default lazy-init, autowire, dependency check settings,
	 * init-method, destroy-method and merge settings. Support nested 'beans'
	 * element use cases by falling back to the given parent in case the
	 * defaults are not explicitly set locally.
	 * @see #populateDefaults(DocumentDefaultsDefinition, DocumentDefaultsDefinition, org.w3c.dom.Element)
	 * @see #getDefaults()
	 */
	public void initDefaults(Element root, @Nullable BeanDefinitionParserDelegate parent) {
		//填充默认值, 如果 parent 不为 null, 则获取 parent.defaults 对象, 如果 parent 为 null, 则返回 null
		populateDefaults(this.defaults, (parent != null ? parent.defaults : null), root);
		//填充完成, 通知一个 默认注册事件
		this.readerContext.fireDefaultsRegistered(this.defaults);
	}

	/**
	 * 填充默认值
	 * - 具体的思路是如果没有配置特殊值, 如果父 BeanDefinition 定义的默认值, 则直接用, 否则根据具体参数给定一个默认值
	 * Populate the given DocumentDefaultsDefinition instance with the default lazy-init,
	 * autowire, dependency check settings, init-method, destroy-method and merge settings.
	 * Support nested 'beans' element use cases by falling back to {@code parentDefaults}
	 * in case the defaults are not explicitly set locally.
	 * @param defaults the defaults to populate
	 * @param parentDefaults the parent BeanDefinitionParserDelegate (if any) defaults to fall back to
	 * @param root the root element of the current bean definition document (or nested beans element)
	 */
	protected void populateDefaults(DocumentDefaultsDefinition defaults, @Nullable DocumentDefaultsDefinition parentDefaults, Element root) {
		//为什么要从 root 节点上获取值呢, 因为 <beans> 节点上可以配置下面这些默认属性
		//获取 default-lazy-init 属性
		String lazyInit = root.getAttribute(DEFAULT_LAZY_INIT_ATTRIBUTE);
		//如果配置是默认值
		if (isDefaultValue(lazyInit)) {
			//获取 parentDefaults 的值或 false
			// Potentially inherited from outer <beans> sections, otherwise falling back to false.
			lazyInit = (parentDefaults != null ? parentDefaults.getLazyInit() : FALSE_VALUE);
		}
		defaults.setLazyInit(lazyInit);

		//获取 default-merge 属性
		String merge = root.getAttribute(DEFAULT_MERGE_ATTRIBUTE);
		//如果是默认值
		if (isDefaultValue(merge)) {
			//获取 parentDefaults 的值或 false
			// Potentially inherited from outer <beans> sections, otherwise falling back to false.
			merge = (parentDefaults != null ? parentDefaults.getMerge() : FALSE_VALUE);
		}
		defaults.setMerge(merge);

		//获取 default-autowire 属性
		String autowire = root.getAttribute(DEFAULT_AUTOWIRE_ATTRIBUTE);
		//如果是默认值, 则获取
		if (isDefaultValue(autowire)) {
			//获取 parentDefaults 对应的值或 false
			// Potentially inherited from outer <beans> sections, otherwise falling back to 'no'.
			autowire = (parentDefaults != null ? parentDefaults.getAutowire() : AUTOWIRE_NO_VALUE);
		}
		defaults.setAutowire(autowire);

		//如果有配置 default-autowire-candidates 属性
		if (root.hasAttribute(DEFAULT_AUTOWIRE_CANDIDATES_ATTRIBUTE)) {
			//设置
			defaults.setAutowireCandidates(root.getAttribute(DEFAULT_AUTOWIRE_CANDIDATES_ATTRIBUTE));
		}
		//如果 parentDefaults 不为 null
		else if (parentDefaults != null) {
			//获取 parentDefaults 对应的值设置
			defaults.setAutowireCandidates(parentDefaults.getAutowireCandidates());
		}

		//如果有 default-init-method 属性, 则设置, 否则获取 parentDefaults 对应的属性
		if (root.hasAttribute(DEFAULT_INIT_METHOD_ATTRIBUTE)) {
			defaults.setInitMethod(root.getAttribute(DEFAULT_INIT_METHOD_ATTRIBUTE));
		}
		else if (parentDefaults != null) {
			defaults.setInitMethod(parentDefaults.getInitMethod());
		}

		//如果有 default-destroy-method 属性, 则设置, 否则获取 parentDefaults 对应的属性
		if (root.hasAttribute(DEFAULT_DESTROY_METHOD_ATTRIBUTE)) {
			defaults.setDestroyMethod(root.getAttribute(DEFAULT_DESTROY_METHOD_ATTRIBUTE));
		}
		else if (parentDefaults != null) {
			defaults.setDestroyMethod(parentDefaults.getDestroyMethod());
		}

		//处理 source 对象
		defaults.setSource(this.readerContext.extractSource(root));
	}

	/**
	 * Return the defaults definition object.
	 */
	public DocumentDefaultsDefinition getDefaults() {
		return this.defaults;
	}

	/**
	 * Return the default settings for bean definitions as indicated within
	 * the attributes of the top-level {@code <beans/>} element.
	 */
	public BeanDefinitionDefaults getBeanDefinitionDefaults() {
		BeanDefinitionDefaults bdd = new BeanDefinitionDefaults();
		bdd.setLazyInit(TRUE_VALUE.equalsIgnoreCase(this.defaults.getLazyInit()));
		bdd.setAutowireMode(getAutowireMode(DEFAULT_VALUE));
		bdd.setInitMethodName(this.defaults.getInitMethod());
		bdd.setDestroyMethodName(this.defaults.getDestroyMethod());
		return bdd;
	}

	/**
	 * Return any patterns provided in the 'default-autowire-candidates'
	 * attribute of the top-level {@code <beans/>} element.
	 */
	@Nullable
	public String[] getAutowireCandidatePatterns() {
		String candidatePattern = this.defaults.getAutowireCandidates();
		return (candidatePattern != null ? StringUtils.commaDelimitedListToStringArray(candidatePattern) : null);
	}


	/**
	 * Parses the supplied {@code <bean>} element. May return {@code null}
	 * if there were errors during parse. Errors are reported to the
	 * {@link org.springframework.beans.factory.parsing.ProblemReporter}.
	 */
	@Nullable
	public BeanDefinitionHolder parseBeanDefinitionElement(Element ele) {
		return parseBeanDefinitionElement(ele, null);
	}

	/**
	 * Parses the supplied {@code <bean>} element. May return {@code null}
	 * if there were errors during parse. Errors are reported to the
	 * {@link org.springframework.beans.factory.parsing.ProblemReporter}.
	 */
	@Nullable
	public BeanDefinitionHolder parseBeanDefinitionElement(Element ele, @Nullable BeanDefinition containingBean) {
		//获取 id 属性
		String id = ele.getAttribute(ID_ATTRIBUTE);
		//获取 name 属性
		String nameAttr = ele.getAttribute(NAME_ATTRIBUTE);

		//计算别名集合
		List<String> aliases = new ArrayList<>();
		//如果名称不为空
		if (StringUtils.hasLength(nameAttr)) {
			//用分割符切割
			String[] nameArr = StringUtils.tokenizeToStringArray(nameAttr, MULTI_VALUE_ATTRIBUTE_DELIMITERS);
			//添加到别名集合
			aliases.addAll(Arrays.asList(nameArr));
		}

		//优先使用 id 作为 beanName
		String beanName = id;
		//如果 beanName 为空, 然后别名不为空
		if (!StringUtils.hasText(beanName) && !aliases.isEmpty()) {
			//获取第一个别名做为 beanName
			beanName = aliases.remove(0);
			if (logger.isTraceEnabled()) {
				logger.trace("No XML 'id' specified - using '" + beanName +
						"' as bean name and " + aliases + " as aliases");
			}
		}

		//如果 containingBean 为 null
		if (containingBean == null) {
			//检查 beanName 的唯一性
			checkNameUniqueness(beanName, aliases, ele);
		}

		//解析属性, 构造 AbstractBeanDefinition
		AbstractBeanDefinition beanDefinition = parseBeanDefinitionElement(ele, beanName, containingBean);
		//如果 beanDefinition 不为 null
		if (beanDefinition != null) {
			// beanName 如果为空的话
			if (!StringUtils.hasText(beanName)) {
				try {
					//如果上层的 containingBean 不为 null
					if (containingBean != null) {
						//则生成一个 innerBean 的唯一 beanName
						beanName = BeanDefinitionReaderUtils.generateBeanName(
								beanDefinition, this.readerContext.getRegistry(), true);
					}
					else {
						//生成一个唯一 beanName
						beanName = this.readerContext.generateBeanName(beanDefinition);
						// Register an alias for the plain bean class name, if still possible,
						// if the generator returned the class name plus a suffix.
						// This is expected for Spring 1.2/2.0 backwards compatibility.
						//获取类的名称
						String beanClassName = beanDefinition.getBeanClassName();
						//如果 beanClassName 不为 null, 且 beanName 是以 beanClassName 开始的,
						// 且 beanName 长度是大于 beanNameClass, 且 beanClassName 未被使用
						//则表明, beanName 是在后面拼接了数字的
						if (beanClassName != null &&
								beanName.startsWith(beanClassName) && beanName.length() > beanClassName.length() &&
								!this.readerContext.getRegistry().isBeanNameInUse(beanClassName)) {
							//如果 beanName 后面拼接了东西, 且 beanClassName 未有被使用, 则将 beanClassName 当作别名
							aliases.add(beanClassName);
						}
					}
					if (logger.isTraceEnabled()) {
						logger.trace("Neither XML 'id' nor 'name' specified - " +
								"using generated bean name [" + beanName + "]");
					}
				}
				catch (Exception ex) {
					error(ex.getMessage(), ele);
					return null;
				}
			}
			//列表变数组
			String[] aliasesArray = StringUtils.toStringArray(aliases);
			//创建 BeanDefinitionHolder
			return new BeanDefinitionHolder(beanDefinition, beanName, aliasesArray);
		}

		//默认返回 null
		return null;
	}

	/**
	 * 检查给定的 beanName 和 别名, 是否唯一
	 * Validate that the specified bean name and aliases have not been used already
	 * within the current level of beans element nesting.
	 */
	protected void checkNameUniqueness(String beanName, List<String> aliases, Element beanElement) {
		//寻找已经使用的 beanName
		String foundName = null;

		//如果 beanName 不为空且 beanName 已经被使用
		if (StringUtils.hasText(beanName) && this.usedNames.contains(beanName)) {
			foundName = beanName;
		}
		//如果没有
		if (foundName == null) {
			//寻找别名中已经存在的名称
			foundName = CollectionUtils.findFirstMatch(this.usedNames, aliases);
		}
		//如果 beanName 已经被使用, 使用 problemReporter 提示错误
		if (foundName != null) {
			error("Bean name '" + foundName + "' is already used in this <beans> element", beanElement);
		}

		//记录已经使用的名称
		this.usedNames.add(beanName);
		this.usedNames.addAll(aliases);
	}

	/**
	 * Parse the bean definition itself, without regard to name or aliases. May return
	 * {@code null} if problems occurred during the parsing of the bean definition.
	 */
	@Nullable
	public AbstractBeanDefinition parseBeanDefinitionElement(
			Element ele, String beanName, @Nullable BeanDefinition containingBean) {

		this.parseState.push(new BeanEntry(beanName));

		//解析 class 属性
		String className = null;
		if (ele.hasAttribute(CLASS_ATTRIBUTE)) {
			className = ele.getAttribute(CLASS_ATTRIBUTE).trim();
		}
		//解析 parent 属性
		String parent = null;
		if (ele.hasAttribute(PARENT_ATTRIBUTE)) {
			parent = ele.getAttribute(PARENT_ATTRIBUTE);
		}

		try {
			// 创建用于承载属性的 AbstractBeanDefinition 实例
			AbstractBeanDefinition bd = createBeanDefinition(className, parent);

			//解析默认 bean 的各种属性
			parseBeanDefinitionAttributes(ele, beanName, containingBean, bd);
			//提取 <description> 节点的文本
			bd.setDescription(DomUtils.getChildElementValueByTagName(ele, DESCRIPTION_ELEMENT));

			//解析 <meta>
			parseMetaElements(ele, bd);
			//解析 <lookup-method> 节点
			parseLookupOverrideSubElements(ele, bd.getMethodOverrides());
			//解析 <replaced-method> 节点
			parseReplacedMethodSubElements(ele, bd.getMethodOverrides());

			//解析 <constructor-arg> 节点
			parseConstructorArgElements(ele, bd);
			//解析 <property> 节点
			parsePropertyElements(ele, bd);
			//解析 <qualifier> 节点
			parseQualifierElements(ele, bd);

			bd.setResource(this.readerContext.getResource());
			bd.setSource(extractSource(ele));

			//返回 AbstractBeanDefinition
			return bd;
		}
		catch (ClassNotFoundException ex) {
			error("Bean class [" + className + "] not found", ele, ex);
		}
		catch (NoClassDefFoundError err) {
			error("Class that bean class [" + className + "] depends on not found", ele, err);
		}
		catch (Throwable ex) {
			error("Unexpected failure during bean definition parsing", ele, ex);
		}
		finally {
			this.parseState.pop();
		}

		return null;
	}

	/**
	 * 对 bean 标签的所有属性进行解析
	 * Apply the attributes of the given bean element to the given bean * definition.
	 * @param ele bean declaration element
	 * @param beanName bean name
	 * @param containingBean containing bean definition
	 * @return a bean definition initialized according to the bean element attributes
	 */
	public AbstractBeanDefinition parseBeanDefinitionAttributes(Element ele, String beanName,
			@Nullable BeanDefinition containingBean, AbstractBeanDefinition bd) {

		if (ele.hasAttribute(SINGLETON_ATTRIBUTE)) {
			error("Old 1.x 'singleton' attribute in use - upgrade to 'scope' declaration", ele);
		}
		//解析 scope 属性
		else if (ele.hasAttribute(SCOPE_ATTRIBUTE)) {
			bd.setScope(ele.getAttribute(SCOPE_ATTRIBUTE));
		}
		//如果父 bean 不为 null
		else if (containingBean != null) {
			// Take default from containing bean in case of an inner bean definition.
			bd.setScope(containingBean.getScope());
		}

		//解析 abstract 属性
		if (ele.hasAttribute(ABSTRACT_ATTRIBUTE)) {
			bd.setAbstract(TRUE_VALUE.equals(ele.getAttribute(ABSTRACT_ATTRIBUTE)));
		}

		//解析 lazy-init 属性
		String lazyInit = ele.getAttribute(LAZY_INIT_ATTRIBUTE);
		//如果是默认值
		if (isDefaultValue(lazyInit)) {
			lazyInit = this.defaults.getLazyInit();
		}
		bd.setLazyInit(TRUE_VALUE.equals(lazyInit));

		//解析 autowire 属性
		String autowire = ele.getAttribute(AUTOWIRE_ATTRIBUTE);
		bd.setAutowireMode(getAutowireMode(autowire));

		//解析 depends-on 属性
		if (ele.hasAttribute(DEPENDS_ON_ATTRIBUTE)) {
			//获取属性
			String dependsOn = ele.getAttribute(DEPENDS_ON_ATTRIBUTE);
			//分割符分割属性为数组再设置
			bd.setDependsOn(StringUtils.tokenizeToStringArray(dependsOn, MULTI_VALUE_ATTRIBUTE_DELIMITERS));
		}

		//解析 autowire-candidate 属性
		String autowireCandidate = ele.getAttribute(AUTOWIRE_CANDIDATE_ATTRIBUTE);
		//如果是默认
		if (isDefaultValue(autowireCandidate)) {
			//获取默认值
			String candidatePattern = this.defaults.getAutowireCandidates();
			//如果默认值不为 null
			if (candidatePattern != null) {
				//逗号分割成数组
				String[] patterns = StringUtils.commaDelimitedListToStringArray(candidatePattern);
				bd.setAutowireCandidate(PatternMatchUtils.simpleMatch(patterns, beanName));
			}
		}
		//如果不是默认
		else {
			//直接设置
			bd.setAutowireCandidate(TRUE_VALUE.equals(autowireCandidate));
		}

		//解析 primary 属性
		if (ele.hasAttribute(PRIMARY_ATTRIBUTE)) {
			bd.setPrimary(TRUE_VALUE.equals(ele.getAttribute(PRIMARY_ATTRIBUTE)));
		}

		//解析 init-method 属性
		if (ele.hasAttribute(INIT_METHOD_ATTRIBUTE)) {
			String initMethodName = ele.getAttribute(INIT_METHOD_ATTRIBUTE);
			bd.setInitMethodName(initMethodName);
		}
		//如果没有配置, 且 initMethod 有默认值
		else if (this.defaults.getInitMethod() != null) {
			//设置 initMethod
			bd.setInitMethodName(this.defaults.getInitMethod());
			//设置不强制执行初始化方法
			bd.setEnforceInitMethod(false);
		}

		//解析 destroy-method 属性
		if (ele.hasAttribute(DESTROY_METHOD_ATTRIBUTE)) {
			String destroyMethodName = ele.getAttribute(DESTROY_METHOD_ATTRIBUTE);
			bd.setDestroyMethodName(destroyMethodName);
		}
		//如果没有配置, 但默认配置中存在 destroyMethod 属性
		else if (this.defaults.getDestroyMethod() != null) {
			//设置默认值
			bd.setDestroyMethodName(this.defaults.getDestroyMethod());
			//设置不强制执行销毁方法
			bd.setEnforceDestroyMethod(false);
		}

		//解析 factory-method 属性
		if (ele.hasAttribute(FACTORY_METHOD_ATTRIBUTE)) {
			bd.setFactoryMethodName(ele.getAttribute(FACTORY_METHOD_ATTRIBUTE));
		}
		//解析 factory-bean 属性
		if (ele.hasAttribute(FACTORY_BEAN_ATTRIBUTE)) {
			bd.setFactoryBeanName(ele.getAttribute(FACTORY_BEAN_ATTRIBUTE));
		}

		return bd;
	}

	/**
	 * 创建 BeanDefinition
	 * Create a bean definition for the given class name and parent name.
	 * @param className the name of the bean class
	 * @param parentName the name of the bean's parent bean
	 * @return the newly created bean definition
	 * @throws ClassNotFoundException if bean class resolution was attempted but failed
	 */
	protected AbstractBeanDefinition createBeanDefinition(@Nullable String className, @Nullable String parentName)
			throws ClassNotFoundException {

		//创建 BeanDefinition 对象
		return BeanDefinitionReaderUtils.createBeanDefinition(
				parentName, className, this.readerContext.getBeanClassLoader());
	}

	/**
	 * meta : 元数据。当需要使用里面的信息时可以通过 key 获取
	 * Parse the meta elements underneath the given element, if any.
	 */
	public void parseMetaElements(Element ele, BeanMetadataAttributeAccessor attributeAccessor) {
		//获取所有子节点
		NodeList nl = ele.getChildNodes();
		//遍历子节点
		for (int i = 0; i < nl.getLength(); i++) {
			Node node = nl.item(i);
			//如果是合法的 element, 且标签名为 <meta>
			if (isCandidateElement(node) && nodeNameEquals(node, META_ELEMENT)) {
				//强转成 Element
				Element metaElement = (Element) node;
				//设置 key
				String key = metaElement.getAttribute(KEY_ATTRIBUTE);
				//设置 value
				String value = metaElement.getAttribute(VALUE_ATTRIBUTE);
				//根据 kv 创建一个 BeanMetadataAttribute
				BeanMetadataAttribute attribute = new BeanMetadataAttribute(key, value);
				//原对象
				attribute.setSource(extractSource(metaElement));
				//设置到 attributeAccessor
				attributeAccessor.addMetadataAttribute(attribute);
			}
		}
	}

	/**
	 * Parse the given autowire attribute value into
	 * {@link AbstractBeanDefinition} autowire constants.
	 */
	@SuppressWarnings("deprecation")
	public int getAutowireMode(String attrValue) {
		String attr = attrValue;
		if (isDefaultValue(attr)) {
			attr = this.defaults.getAutowire();
		}
		int autowire = AbstractBeanDefinition.AUTOWIRE_NO;
		if (AUTOWIRE_BY_NAME_VALUE.equals(attr)) {
			autowire = AbstractBeanDefinition.AUTOWIRE_BY_NAME;
		}
		else if (AUTOWIRE_BY_TYPE_VALUE.equals(attr)) {
			autowire = AbstractBeanDefinition.AUTOWIRE_BY_TYPE;
		}
		else if (AUTOWIRE_CONSTRUCTOR_VALUE.equals(attr)) {
			autowire = AbstractBeanDefinition.AUTOWIRE_CONSTRUCTOR;
		}
		else if (AUTOWIRE_AUTODETECT_VALUE.equals(attr)) {
			autowire = AbstractBeanDefinition.AUTOWIRE_AUTODETECT;
		}
		// Else leave default value.
		return autowire;
	}

	/**
	 * Parse constructor-arg sub-elements of the given bean element.
	 */
	public void parseConstructorArgElements(Element beanEle, BeanDefinition bd) {
		//获取子节点
		NodeList nl = beanEle.getChildNodes();
		//遍历子节点
		for (int i = 0; i < nl.getLength(); i++) {
			Node node = nl.item(i);
			//如果合法节点且是 constructor-arg 名称
			if (isCandidateElement(node) && nodeNameEquals(node, CONSTRUCTOR_ARG_ELEMENT)) {
				//处理构造参数
				parseConstructorArgElement((Element) node, bd);
			}
		}
	}

	/**
	 * Parse property sub-elements of the given bean element.
	 */
	public void parsePropertyElements(Element beanEle, BeanDefinition bd) {
		NodeList nl = beanEle.getChildNodes();
		for (int i = 0; i < nl.getLength(); i++) {
			Node node = nl.item(i);
			if (isCandidateElement(node) && nodeNameEquals(node, PROPERTY_ELEMENT)) {
				parsePropertyElement((Element) node, bd);
			}
		}
	}

	/**
	 * Parse qualifier sub-elements of the given bean element.
	 */
	public void parseQualifierElements(Element beanEle, AbstractBeanDefinition bd) {
		NodeList nl = beanEle.getChildNodes();
		for (int i = 0; i < nl.getLength(); i++) {
			Node node = nl.item(i);
			if (isCandidateElement(node) && nodeNameEquals(node, QUALIFIER_ELEMENT)) {
				parseQualifierElement((Element) node, bd);
			}
		}
	}

	/**
	 * Parse lookup-override sub-elements of the given bean element.
	 */
	public void parseLookupOverrideSubElements(Element beanEle, MethodOverrides overrides) {
		//获取所有子节点
		NodeList nl = beanEle.getChildNodes();
		//遍历
		for (int i = 0; i < nl.getLength(); i++) {
			//获取 node
			Node node = nl.item(i);
			//如果是合法的 node , 则名称为 lookup-method
			if (isCandidateElement(node) && nodeNameEquals(node, LOOKUP_METHOD_ELEMENT)) {
				//强转
				Element ele = (Element) node;
				//获取方法名
				String methodName = ele.getAttribute(NAME_ATTRIBUTE);
				//获取 bean 引用
				String beanRef = ele.getAttribute(BEAN_ELEMENT);
				//创建 LookupOverride
				LookupOverride override = new LookupOverride(methodName, beanRef);
				//设置源对象
				override.setSource(extractSource(ele));
				//添加到 overrides 中
				overrides.addOverride(override);
			}
		}
	}

	/**
	 * Parse replaced-method sub-elements of the given bean element.
	 */
	public void parseReplacedMethodSubElements(Element beanEle, MethodOverrides overrides) {
		//获取子节点列表
		NodeList nl = beanEle.getChildNodes();
		//遍历子节点
		for (int i = 0; i < nl.getLength(); i++) {
			//获取子节点
			Node node = nl.item(i);
			//如果是合法节点并且节点名称为 replaced-method
			if (isCandidateElement(node) && nodeNameEquals(node, REPLACED_METHOD_ELEMENT)) {
				//强转
				Element replacedMethodEle = (Element) node;
				//获取 name 配置
				String name = replacedMethodEle.getAttribute(NAME_ATTRIBUTE);
				//获取 replacer 配置
				String callback = replacedMethodEle.getAttribute(REPLACER_ATTRIBUTE);
				//创建 ReplaceOverride 对象
				ReplaceOverride replaceOverride = new ReplaceOverride(name, callback);
				//获取子元素 arg-type 列表
				// Look for arg-type match elements.
				List<Element> argTypeEles = DomUtils.getChildElementsByTagName(replacedMethodEle, ARG_TYPE_ELEMENT);
				//遍历
				for (Element argTypeEle : argTypeEles) {
					//获取 match 属性
					String match = argTypeEle.getAttribute(ARG_TYPE_MATCH_ATTRIBUTE);
					//获取参数类型, 如: <arg-type match="String"/> 或 <arg-type/>String</arg-type>
					match = (StringUtils.hasText(match) ? match : DomUtils.getTextValue(argTypeEle));
					//如果 match 不为空
					if (StringUtils.hasText(match)) {
						//记录
						replaceOverride.addTypeIdentifier(match);
					}
				}
				//设置 source 对象
				replaceOverride.setSource(extractSource(replacedMethodEle));
				//添加到 overrides 中
				overrides.addOverride(replaceOverride);
			}
		}
	}

	/**
	 * Parse a constructor-arg element.
	 */
	public void parseConstructorArgElement(Element ele, BeanDefinition bd) {
		//获取 index
		String indexAttr = ele.getAttribute(INDEX_ATTRIBUTE);
		//获取 type
		String typeAttr = ele.getAttribute(TYPE_ATTRIBUTE);
		//获取 name 属性
		String nameAttr = ele.getAttribute(NAME_ATTRIBUTE);
		//如果有配置 index 属性
		if (StringUtils.hasLength(indexAttr)) {
			try {
				//转 index 成数字
				int index = Integer.parseInt(indexAttr);
				// index 不能小于 0
				if (index < 0) {
					error("'index' cannot be lower than 0", ele);
				}
				else {
					try {
						this.parseState.push(new ConstructorArgumentEntry(index));
						//解析 ele 对应属性元素
						Object value = parsePropertyValue(ele, bd, null);
						//创建 ValueHolder 对象
						ConstructorArgumentValues.ValueHolder valueHolder = new ConstructorArgumentValues.ValueHolder(value);
						//如果 typeAttr 不为空
						if (StringUtils.hasLength(typeAttr)) {
							//设置 typeAttr
							valueHolder.setType(typeAttr);
						}
						//如果 nameAttr 不为空
						if (StringUtils.hasLength(nameAttr)) {
							valueHolder.setName(nameAttr);
						}
						//设置 source
						valueHolder.setSource(extractSource(ele));
						//如果 index 配置重复, 则报错
						if (bd.getConstructorArgumentValues().hasIndexedArgumentValue(index)) {
							error("Ambiguous constructor-arg entries for index " + index, ele);
						}
						else {
							//添加指定索引的构造参数
							bd.getConstructorArgumentValues().addIndexedArgumentValue(index, valueHolder);
						}
					}
					finally {
						this.parseState.pop();
					}
				}
			}
			catch (NumberFormatException ex) {
				//如果转数字异常, 则报错
				error("Attribute 'index' of tag 'constructor-arg' must be an integer", ele);
			}
		}
		else {
			try {
				this.parseState.push(new ConstructorArgumentEntry());
				//解析元素值
				Object value = parsePropertyValue(ele, bd, null);
				//创建 ValueHolder
				ConstructorArgumentValues.ValueHolder valueHolder = new ConstructorArgumentValues.ValueHolder(value);
				//如果 type 不为空, 则设置
				if (StringUtils.hasLength(typeAttr)) {
					valueHolder.setType(typeAttr);
				}
				//如果 name 不为空, 则设置
				if (StringUtils.hasLength(nameAttr)) {
					valueHolder.setName(nameAttr);
				}
				//设置 source
				valueHolder.setSource(extractSource(ele));
				//添加参数
				bd.getConstructorArgumentValues().addGenericArgumentValue(valueHolder);
			}
			finally {
				this.parseState.pop();
			}
		}
	}

	/**
	 * Parse a property element.
	 */
	public void parsePropertyElement(Element ele, BeanDefinition bd) {
		String propertyName = ele.getAttribute(NAME_ATTRIBUTE);
		if (!StringUtils.hasLength(propertyName)) {
			error("Tag 'property' must have a 'name' attribute", ele);
			return;
		}
		this.parseState.push(new PropertyEntry(propertyName));
		try {
			if (bd.getPropertyValues().contains(propertyName)) {
				error("Multiple 'property' definitions for property '" + propertyName + "'", ele);
				return;
			}
			Object val = parsePropertyValue(ele, bd, propertyName);
			PropertyValue pv = new PropertyValue(propertyName, val);
			parseMetaElements(ele, pv);
			pv.setSource(extractSource(ele));
			bd.getPropertyValues().addPropertyValue(pv);
		}
		finally {
			this.parseState.pop();
		}
	}

	/**
	 * Parse a qualifier element.
	 */
	public void parseQualifierElement(Element ele, AbstractBeanDefinition bd) {
		String typeName = ele.getAttribute(TYPE_ATTRIBUTE);
		if (!StringUtils.hasLength(typeName)) {
			error("Tag 'qualifier' must have a 'type' attribute", ele);
			return;
		}
		this.parseState.push(new QualifierEntry(typeName));
		try {
			AutowireCandidateQualifier qualifier = new AutowireCandidateQualifier(typeName);
			qualifier.setSource(extractSource(ele));
			String value = ele.getAttribute(VALUE_ATTRIBUTE);
			if (StringUtils.hasLength(value)) {
				qualifier.setAttribute(AutowireCandidateQualifier.VALUE_KEY, value);
			}
			NodeList nl = ele.getChildNodes();
			for (int i = 0; i < nl.getLength(); i++) {
				Node node = nl.item(i);
				if (isCandidateElement(node) && nodeNameEquals(node, QUALIFIER_ATTRIBUTE_ELEMENT)) {
					Element attributeEle = (Element) node;
					String attributeName = attributeEle.getAttribute(KEY_ATTRIBUTE);
					String attributeValue = attributeEle.getAttribute(VALUE_ATTRIBUTE);
					if (StringUtils.hasLength(attributeName) && StringUtils.hasLength(attributeValue)) {
						BeanMetadataAttribute attribute = new BeanMetadataAttribute(attributeName, attributeValue);
						attribute.setSource(extractSource(attributeEle));
						qualifier.addMetadataAttribute(attribute);
					}
					else {
						error("Qualifier 'attribute' tag must have a 'name' and 'value'", attributeEle);
						return;
					}
				}
			}
			bd.addQualifier(qualifier);
		}
		finally {
			this.parseState.pop();
		}
	}

	/**
	 * Get the value of a property element. May be a list etc.
	 * Also used for constructor arguments, "propertyName" being null in this case.
	 */
	@Nullable
	public Object parsePropertyValue(Element ele, BeanDefinition bd, @Nullable String propertyName) {
		String elementName = (propertyName != null ?
				"<property> element for property '" + propertyName + "'" :
				"<constructor-arg> element");

		//获取子节点列表
		// Should only have one child element: ref, value, list, etc.
		NodeList nl = ele.getChildNodes();
		//子节点
		Element subElement = null;
		//遍历
		for (int i = 0; i < nl.getLength(); i++) {
			//获取节点
			Node node = nl.item(i);
			//如果节点是 Element 且名称不是 <description> 且不是 <meta>
			if (node instanceof Element && !nodeNameEquals(node, DESCRIPTION_ELEMENT) &&
					!nodeNameEquals(node, META_ELEMENT)) {
				//如果 subElement 不为空, 则报错, 以此来保证只有一个子节点
				// Child element is what we're looking for.
				if (subElement != null) {
					error(elementName + " must not contain more than one sub-element", ele);
				}
				else {
					//获取子节点
					subElement = (Element) node;
				}
			}
		}

		//判断是否有 ref 属性
		boolean hasRefAttribute = ele.hasAttribute(REF_ATTRIBUTE);
		//判断是否有 value 属性
		boolean hasValueAttribute = ele.hasAttribute(VALUE_ATTRIBUTE);
		//ref , value 和 subElement 三者只能选一个, 否则报错
		if ((hasRefAttribute && hasValueAttribute) ||
				((hasRefAttribute || hasValueAttribute) && subElement != null)) {
			error(elementName +
					" is only allowed to contain either 'ref' attribute OR 'value' attribute OR sub-element", ele);
		}

		//如果有 ref
		if (hasRefAttribute) {
			//获取 refName
			String refName = ele.getAttribute(REF_ATTRIBUTE);
			//如果 refName 为空, 则报错
			if (!StringUtils.hasText(refName)) {
				error(elementName + " contains empty 'ref' attribute", ele);
			}
			//创建 RuntimeBeanReference 对象
			RuntimeBeanReference ref = new RuntimeBeanReference(refName);
			//设置 source
			ref.setSource(extractSource(ele));
			//返回
			return ref;
		}
		// 如果有 value
		else if (hasValueAttribute) {
			//创建 TypedStringValue
			TypedStringValue valueHolder = new TypedStringValue(ele.getAttribute(VALUE_ATTRIBUTE));
			valueHolder.setSource(extractSource(ele));
			//返回
			return valueHolder;
		}
		//如果有子无素, 则继续解析
		else if (subElement != null) {
			//解析子元素
			return parsePropertySubElement(subElement, bd);
		}
		else {
			//三种情况都没有, 则报错
			// Neither child element nor "ref" or "value" attribute found.
			error(elementName + " must specify a ref or value", ele);
			//返回 null
			return null;
		}
	}

	/**
	 * 解析子属性的值
	 * Parse a value, ref or collection sub-element of a property or
	 * constructor-arg element.
	 * @param ele subelement of property element; we don't know which yet
	 * @param bd the current bean definition (if any)
	 */
	@Nullable
	public Object parsePropertySubElement(Element ele, @Nullable BeanDefinition bd) {
		return parsePropertySubElement(ele, bd, null);
	}

	/**
	 * Parse a value, ref or collection sub-element of a property or
	 * constructor-arg element.
	 * @param ele subelement of property element; we don't know which yet
	 * @param bd the current bean definition (if any)
	 * @param defaultValueType the default type (class name) for any
	 * {@code <value>} tag that might be created
	 */
	@Nullable
	public Object parsePropertySubElement(Element ele, @Nullable BeanDefinition bd, @Nullable String defaultValueType) {
		//如果非默认命名参数
		if (!isDefaultNamespace(ele)) {
			//解析嵌套自定义元素
			return parseNestedCustomElement(ele, bd);
		}
		//如果标签是 <bean>
		else if (nodeNameEquals(ele, BEAN_ELEMENT)) {
			//解析出嵌套的 BeanDefinitionHolder
			BeanDefinitionHolder nestedBd = parseBeanDefinitionElement(ele, bd);
			//如果嵌套的 BeanDefinitionHolder 不为 null
			if (nestedBd != null) {
				nestedBd = decorateBeanDefinitionIfRequired(ele, nestedBd, bd);
			}
			return nestedBd;
		}
		//如果标签是 <ref>
		else if (nodeNameEquals(ele, REF_ELEMENT)) {
			//获取引用的 beanName
			// A generic reference to any name of any bean.
			String refName = ele.getAttribute(BEAN_REF_ATTRIBUTE);
			boolean toParent = false;
			//如果没有 refName
			if (!StringUtils.hasLength(refName)) {
				//获取父上下文的 bean
				// A reference to the id of another bean in a parent context.
				refName = ele.getAttribute(PARENT_REF_ATTRIBUTE);
				toParent = true;
				//如果还是没有, 则报错
				if (!StringUtils.hasLength(refName)) {
					error("'bean' or 'parent' is required for <ref> element", ele);
					return null;
				}
			}
			//如果是空白字符串, 则也报错
			if (!StringUtils.hasText(refName)) {
				error("<ref> element contains empty target attribute", ele);
				return null;
			}
			//创建 RuntimeBeanReference
			RuntimeBeanReference ref = new RuntimeBeanReference(refName, toParent);
			//设置 source
			ref.setSource(extractSource(ele));
			return ref;
		}
		//如果是 <idref>
		else if (nodeNameEquals(ele, IDREF_ELEMENT)) {
			//解析 <idref>
			return parseIdRefElement(ele);
		}
		//如果是 <value>
		else if (nodeNameEquals(ele, VALUE_ELEMENT)) {
			//解析
			return parseValueElement(ele, defaultValueType);
		}
		//如果是 <null>
		else if (nodeNameEquals(ele, NULL_ELEMENT)) {
			//创建 null 的 TypedStringValue 对象
			// It's a distinguished null value. Let's wrap it in a TypedStringValue
			// object in order to preserve the source location.
			TypedStringValue nullHolder = new TypedStringValue(null);
			nullHolder.setSource(extractSource(ele));
			return nullHolder;
		}
		//如果是 <array>
		else if (nodeNameEquals(ele, ARRAY_ELEMENT)) {
			//解析数组
			return parseArrayElement(ele, bd);
		}
		//如果是 <list>
		else if (nodeNameEquals(ele, LIST_ELEMENT)) {
			//解析列表类型
			return parseListElement(ele, bd);
		}
		//如果是 <set>
		else if (nodeNameEquals(ele, SET_ELEMENT)) {
			return parseSetElement(ele, bd);
		}
		//如果是 <map>
		else if (nodeNameEquals(ele, MAP_ELEMENT)) {
			return parseMapElement(ele, bd);
		}
		//如果是 <props>
		else if (nodeNameEquals(ele, PROPS_ELEMENT)) {
			return parsePropsElement(ele);
		}
		else {
			//不是以前的标签, 则报错
			error("Unknown property sub-element: [" + ele.getNodeName() + "]", ele);
			return null;
		}
	}

	/**
	 * Return a typed String value Object for the given 'idref' element.
	 */
	@Nullable
	public Object parseIdRefElement(Element ele) {
		//获取 beanName
		// A generic reference to any name of any bean.
		String refName = ele.getAttribute(BEAN_REF_ATTRIBUTE);
		//如果没找到 bean 属性
		if (!StringUtils.hasLength(refName)) {
			//报错
			error("'bean' is required for <idref> element", ele);
			//返回 null
			return null;
		}
		//配置的内容为空
		if (!StringUtils.hasText(refName)) {
			//报错
			error("<idref> element contains empty target attribute", ele);
			return null;
		}
		//创建 RuntimeBeanNameReference
		RuntimeBeanNameReference ref = new RuntimeBeanNameReference(refName);
		ref.setSource(extractSource(ele));
		//返回
		return ref;
	}

	/**
	 * Return a typed String value Object for the given value element.
	 */
	public Object parseValueElement(Element ele, @Nullable String defaultTypeName) {
		//获取节点文本值
		// It's a literal value.
		String value = DomUtils.getTextValue(ele);
		//获取值的类型
		String specifiedTypeName = ele.getAttribute(TYPE_ATTRIBUTE);
		//重新赋值
		String typeName = specifiedTypeName;
		//如果为空, 则用默认值
		if (!StringUtils.hasText(typeName)) {
			typeName = defaultTypeName;
		}
		try {
			TypedStringValue typedValue = buildTypedStringValue(value, typeName);
			//设置 source
			typedValue.setSource(extractSource(ele));
			//设置类型配置
			typedValue.setSpecifiedTypeName(specifiedTypeName);
			//返回
			return typedValue;
		}
		catch (ClassNotFoundException ex) {
			error("Type class [" + typeName + "] not found for <value> element", ele, ex);
			return value;
		}
	}

	/**
	 * Build a typed String value Object for the given raw value.
	 * @see org.springframework.beans.factory.config.TypedStringValue
	 */
	protected TypedStringValue buildTypedStringValue(String value, @Nullable String targetTypeName)
			throws ClassNotFoundException {

		//获取 beanClassLoader
		ClassLoader classLoader = this.readerContext.getBeanClassLoader();
		TypedStringValue typedValue;
		//如果类型为空,
		if (!StringUtils.hasText(targetTypeName)) {
			typedValue = new TypedStringValue(value);
		}
		//如果 classLoader 不为 null
		else if (classLoader != null) {
			//根据类型名, 加载类
			Class<?> targetType = ClassUtils.forName(targetTypeName, classLoader);
			//创建 TypedStringValue
			typedValue = new TypedStringValue(value, targetType);
		}
		else {
			//创建 targetTypeName 为字符串的 TypedStringValue
			typedValue = new TypedStringValue(value, targetTypeName);
		}
		//返回
		return typedValue;
	}

	/**
	 * Parse an array element.
	 */
	public Object parseArrayElement(Element arrayEle, @Nullable BeanDefinition bd) {
		//获取 value-type 的配置
		String elementType = arrayEle.getAttribute(VALUE_TYPE_ATTRIBUTE);
		//获取子节点列表
		NodeList nl = arrayEle.getChildNodes();
		//创建 ManagedArray
		ManagedArray target = new ManagedArray(elementType, nl.getLength());
		//设置 source
		target.setSource(extractSource(arrayEle));
		//设置元素类型
		target.setElementTypeName(elementType);
		//设置是否可以合并
		target.setMergeEnabled(parseMergeAttribute(arrayEle));
		//解析数组元素
		parseCollectionElements(nl, target, bd, elementType);
		return target;
	}

	/**
	 * Parse a list element.
	 */
	public List<Object> parseListElement(Element collectionEle, @Nullable BeanDefinition bd) {
		//获取元素类型
		String defaultElementType = collectionEle.getAttribute(VALUE_TYPE_ATTRIBUTE);
		//获取节点列表
		NodeList nl = collectionEle.getChildNodes();
		//创建 ManagedList
		ManagedList<Object> target = new ManagedList<>(nl.getLength());
		//设置 source
		target.setSource(extractSource(collectionEle));
		//设置元素类型
		target.setElementTypeName(defaultElementType);
		//设置是否可以合并
		target.setMergeEnabled(parseMergeAttribute(collectionEle));
		//解析子元素
		parseCollectionElements(nl, target, bd, defaultElementType);
		//返回解析后的结果
		return target;
	}

	/**
	 * Parse a set element.
	 */
	public Set<Object> parseSetElement(Element collectionEle, @Nullable BeanDefinition bd) {
		//获取元素类型
		String defaultElementType = collectionEle.getAttribute(VALUE_TYPE_ATTRIBUTE);
		//获取子节点列表
		NodeList nl = collectionEle.getChildNodes();
		//创建 ManagedSet
		ManagedSet<Object> target = new ManagedSet<>(nl.getLength());
		//设置 source
		target.setSource(extractSource(collectionEle));
		//设置元素类型
		target.setElementTypeName(defaultElementType);
		//设置是否可以合并
		target.setMergeEnabled(parseMergeAttribute(collectionEle));
		//处理子元素
		parseCollectionElements(nl, target, bd, defaultElementType);
		return target;
	}

	protected void parseCollectionElements(
			NodeList elementNodes, Collection<Object> target, @Nullable BeanDefinition bd, String defaultElementType) {

		//遍历节点列表
		for (int i = 0; i < elementNodes.getLength(); i++) {
			Node node = elementNodes.item(i);
			//如果节点是 Element 类型, 且非 <description>
			if (node instanceof Element && !nodeNameEquals(node, DESCRIPTION_ELEMENT)) {
				//解析子节点
				target.add(parsePropertySubElement((Element) node, bd, defaultElementType));
			}
		}
	}

	/**
	 * Parse a map element.
	 */
	public Map<Object, Object> parseMapElement(Element mapEle, @Nullable BeanDefinition bd) {
		//获取 key 的类型
		String defaultKeyType = mapEle.getAttribute(KEY_TYPE_ATTRIBUTE);
		//获取 val 的类型
		String defaultValueType = mapEle.getAttribute(VALUE_TYPE_ATTRIBUTE);

		//获取 entry 元素列表
		List<Element> entryEles = DomUtils.getChildElementsByTagName(mapEle, ENTRY_ELEMENT);
		//创建 ManagedMap
		ManagedMap<Object, Object> map = new ManagedMap<>(entryEles.size());
		//设置 source
		map.setSource(extractSource(mapEle));
		//设置 key 类型
		map.setKeyTypeName(defaultKeyType);
		//设置 value 类型
		map.setValueTypeName(defaultValueType);
		//设置是否可以合并
		map.setMergeEnabled(parseMergeAttribute(mapEle));

		//遍历所有元素
		for (Element entryEle : entryEles) {
			//获取子元素列表, 理论是应该只有个子元素, 如: ref, value, list 等
			// Should only have one value child element: ref, value, list, etc.
			// Optionally, there might be a key child element.
			NodeList entrySubNodes = entryEle.getChildNodes();
			Element keyEle = null;
			Element valueEle = null;
			//遍历子节点
			for (int j = 0; j < entrySubNodes.getLength(); j++) {
				Node node = entrySubNodes.item(j);
				//如果子节点是 node
				if (node instanceof Element) {
					//强转
					Element candidateEle = (Element) node;
					//如果节点名称为 key
					if (nodeNameEquals(candidateEle, KEY_ELEMENT)) {
						//如果 key 不为 null, 则报错, 因为一个 entry 只能有一个 key
						if (keyEle != null) {
							error("<entry> element is only allowed to contain one <key> sub-element", entryEle);
						}
						else {
							//缓存
							keyEle = candidateEle;
						}
					}
					else {
						//如果是 <description> , 则不做处理
						// Child element is what we're looking for.
						if (nodeNameEquals(candidateEle, DESCRIPTION_ELEMENT)) {
							// the element is a <description> -> ignore it
						}
						//如果 valueEle 不为null, 则报错, 因为一个 <entry> 只能有一个 <value>
						else if (valueEle != null) {
							error("<entry> element must not contain more than one value sub-element", entryEle);
						}
						else {
							//设置 <value> 元素
							valueEle = candidateEle;
						}
					}
				}
			}

			// Extract key from attribute or sub-element.
			Object key = null;
			// <entry> 元素上是否有 key 属性
			boolean hasKeyAttribute = entryEle.hasAttribute(KEY_ATTRIBUTE);
			// <entry> 元素上是否有 key-ref 属性
			boolean hasKeyRefAttribute = entryEle.hasAttribute(KEY_REF_ATTRIBUTE);
			//  key, key-ref 和 <key> 三者只能有一个, 否则报错
			if ((hasKeyAttribute && hasKeyRefAttribute) ||
					(hasKeyAttribute || hasKeyRefAttribute) && keyEle != null) {
				error("<entry> element is only allowed to contain either " +
						"a 'key' attribute OR a 'key-ref' attribute OR a <key> sub-element", entryEle);
			}
			//如果是 <entry key=""/> 这种标签
			if (hasKeyAttribute) {
				//获取 key 的值, 并且创建 TypedStringValue
				key = buildTypedStringValueForMap(entryEle.getAttribute(KEY_ATTRIBUTE), defaultKeyType, entryEle);
			}
			//如果有 key-ref
			else if (hasKeyRefAttribute) {
				//获取引用的 beanName
				String refName = entryEle.getAttribute(KEY_REF_ATTRIBUTE);
				//如果没有填值, 则报错
				if (!StringUtils.hasText(refName)) {
					error("<entry> element contains empty 'key-ref' attribute", entryEle);
				}
				//创建 RuntimeBeanReference
				RuntimeBeanReference ref = new RuntimeBeanReference(refName);
				//设置 Source
				ref.setSource(extractSource(entryEle));
				key = ref;
			}
			//如果有子节点
			else if (keyEle != null) {
				//解析子节点
				key = parseKeyElement(keyEle, bd, defaultKeyType);
			}
			else {
				//如果三者都没有, 则报错
				error("<entry> element must specify a key", entryEle);
			}

			// Extract value from attribute or sub-element.
			Object value = null;
			//是否有 value 属性
			boolean hasValueAttribute = entryEle.hasAttribute(VALUE_ATTRIBUTE);
			//是否有 value-ref 属性
			boolean hasValueRefAttribute = entryEle.hasAttribute(VALUE_REF_ATTRIBUTE);
			//是否有 value-type 属性
			boolean hasValueTypeAttribute = entryEle.hasAttribute(VALUE_TYPE_ATTRIBUTE);
			// value, value-ref 和 子节点 三者只能有一个, 否则报错
			if ((hasValueAttribute && hasValueRefAttribute) ||
					(hasValueAttribute || hasValueRefAttribute) && valueEle != null) {
				error("<entry> element is only allowed to contain either " +
						"'value' attribute OR 'value-ref' attribute OR <value> sub-element", entryEle);
			}
			// 同时有 value-type 和 value-ref 或 有 value-type 没 value 或 有 value-type 也有子元素, 则报错
			if ((hasValueTypeAttribute && hasValueRefAttribute) ||
				(hasValueTypeAttribute && !hasValueAttribute) ||
					(hasValueTypeAttribute && valueEle != null)) {
				error("<entry> element is only allowed to contain a 'value-type' " +
						"attribute when it has a 'value' attribute", entryEle);
			}
			//如果有 value 属性
			if (hasValueAttribute) {
				//获取 value-type 的值
				String valueType = entryEle.getAttribute(VALUE_TYPE_ATTRIBUTE);
				//如果为空, 则用默认的
				if (!StringUtils.hasText(valueType)) {
					valueType = defaultValueType;
				}
				//创建 TypedStringValue
				value = buildTypedStringValueForMap(entryEle.getAttribute(VALUE_ATTRIBUTE), valueType, entryEle);
			}
			//如果有 value-ref 属性
			else if (hasValueRefAttribute) {
				//获取引用
				String refName = entryEle.getAttribute(VALUE_REF_ATTRIBUTE);
				//如果 refName 为空, 则报错
				if (!StringUtils.hasText(refName)) {
					error("<entry> element contains empty 'value-ref' attribute", entryEle);
				}
				//创建 RuntimeBeanReference
				RuntimeBeanReference ref = new RuntimeBeanReference(refName);
				//设置 source
				ref.setSource(extractSource(entryEle));
				value = ref;
			}
			else if (valueEle != null) {
				//解析子节点
				value = parsePropertySubElement(valueEle, bd, defaultValueType);
			}
			else {
				//如果三种都没有, 则报错
				error("<entry> element must specify a value", entryEle);
			}

			//添加 key, value 到 map
			// Add final key and value to the Map.
			map.put(key, value);
		}

		return map;
	}

	/**
	 * Build a typed String value Object for the given raw value.
	 * @see org.springframework.beans.factory.config.TypedStringValue
	 */
	protected final Object buildTypedStringValueForMap(String value, String defaultTypeName, Element entryEle) {
		try {
			//创建 TypedStringValue
			TypedStringValue typedValue = buildTypedStringValue(value, defaultTypeName);
			typedValue.setSource(extractSource(entryEle));
			return typedValue;
		}
		catch (ClassNotFoundException ex) {
			error("Type class [" + defaultTypeName + "] not found for Map key/value type", entryEle, ex);
			return value;
		}
	}

	/**
	 * Parse a key sub-element of a map element.
	 */
	@Nullable
	protected Object parseKeyElement(Element keyEle, @Nullable BeanDefinition bd, String defaultKeyTypeName) {
		//获取所有子节点
		NodeList nl = keyEle.getChildNodes();
		Element subElement = null;
		//遍历
		for (int i = 0; i < nl.getLength(); i++) {
			Node node = nl.item(i);
			//如果 node 是 Element
			if (node instanceof Element) {
				//如果 subElement 不为 null, 则报错, <key> 的子节点只能有一个
				// Child element is what we're looking for.
				if (subElement != null) {
					error("<key> element must not contain more than one value sub-element", keyEle);
				}
				else {
					//赋值
					subElement = (Element) node;
				}
			}
		}
		//如果没有节点, 则返回 null
		if (subElement == null) {
			return null;
		}
		//解析子节点, 返回解析的对象
		return parsePropertySubElement(subElement, bd, defaultKeyTypeName);
	}

	/**
	 * Parse a props element.
	 */
	public Properties parsePropsElement(Element propsEle) {
		ManagedProperties props = new ManagedProperties();
		props.setSource(extractSource(propsEle));
		props.setMergeEnabled(parseMergeAttribute(propsEle));

		List<Element> propEles = DomUtils.getChildElementsByTagName(propsEle, PROP_ELEMENT);
		for (Element propEle : propEles) {
			String key = propEle.getAttribute(KEY_ATTRIBUTE);
			// Trim the text value to avoid unwanted whitespace
			// caused by typical XML formatting.
			String value = DomUtils.getTextValue(propEle).trim();
			TypedStringValue keyHolder = new TypedStringValue(key);
			keyHolder.setSource(extractSource(propEle));
			TypedStringValue valueHolder = new TypedStringValue(value);
			valueHolder.setSource(extractSource(propEle));
			props.put(keyHolder, valueHolder);
		}

		return props;
	}

	/**
	 * 获取是否可以合并
	 * Parse the merge attribute of a collection element, if any.
	 */
	public boolean parseMergeAttribute(Element collectionElement) {
		//获取 merge 配置
		String value = collectionElement.getAttribute(MERGE_ATTRIBUTE);
		//如果是默认值, 则从默认值中获取
		if (isDefaultValue(value)) {
			value = this.defaults.getMerge();
		}
		//转换类型返回
		return TRUE_VALUE.equals(value);
	}

	/**
	 * Parse a custom element (outside of the default namespace).
	 * @param ele the element to parse
	 * @return the resulting bean definition
	 */
	@Nullable
	public BeanDefinition parseCustomElement(Element ele) {
		return parseCustomElement(ele, null);
	}

	/**
	 * Parse a custom element (outside of the default namespace).
	 * @param ele the element to parse
	 * @param containingBd the containing bean definition (if any)
	 * @return the resulting bean definition
	 */
	@Nullable
	public BeanDefinition parseCustomElement(Element ele, @Nullable BeanDefinition containingBd) {
		String namespaceUri = getNamespaceURI(ele);
		if (namespaceUri == null) {
			return null;
		}
		NamespaceHandler handler = this.readerContext.getNamespaceHandlerResolver().resolve(namespaceUri);
		if (handler == null) {
			error("Unable to locate Spring NamespaceHandler for XML schema namespace [" + namespaceUri + "]", ele);
			return null;
		}
		return handler.parse(ele, new ParserContext(this.readerContext, this, containingBd));
	}

	/**
	 * Decorate the given bean definition through a namespace handler, if applicable.
	 * @param ele the current element
	 * @param originalDef the current bean definition
	 * @return the decorated bean definition
	 */
	public BeanDefinitionHolder decorateBeanDefinitionIfRequired(Element ele, BeanDefinitionHolder originalDef) {
		return decorateBeanDefinitionIfRequired(ele, originalDef, null);
	}

	/**
	 * 通过自定义的 namespaceHandler 来修饰 BeanDefinition
	 * Decorate the given bean definition through a namespace handler, if applicable.
	 * @param ele the current element
	 * @param originalDef the current bean definition
	 * @param containingBd the containing bean definition (if any)
	 * @return the decorated bean definition
	 */
	public BeanDefinitionHolder decorateBeanDefinitionIfRequired(
			Element ele, BeanDefinitionHolder originalDef, @Nullable BeanDefinition containingBd) {

		// 父 BeanDefinition , 可能为 null
		//获取 BeanDefinitionHolder
		BeanDefinitionHolder finalDefinition = originalDef;

		//获取 ele 的属性列表
		// Decorate based on custom attributes first.
		NamedNodeMap attributes = ele.getAttributes();
		//遍历
		for (int i = 0; i < attributes.getLength(); i++) {
			//获取属性节点
			Node node = attributes.item(i);
			//进行修饰
			finalDefinition = decorateIfRequired(node, finalDefinition, containingBd);
		}

		//获取子节点列表
		// Decorate based on custom nested elements.
		NodeList children = ele.getChildNodes();
		//遍历
		for (int i = 0; i < children.getLength(); i++) {
			Node node = children.item(i);
			//如果节点的类型是 Element
			if (node.getNodeType() == Node.ELEMENT_NODE) {
				//进行修饰
				finalDefinition = decorateIfRequired(node, finalDefinition, containingBd);
			}
		}
		return finalDefinition;
	}

	/**
	 * 通过自定义命名空间处理器来修饰 BeanDefinitionHolder
	 * Decorate the given bean definition through a namespace handler,
	 * if applicable.
	 * @param node the current child node
	 * @param originalDef the current bean definition
	 * @param containingBd the containing bean definition (if any)
	 * @return the decorated bean definition
	 */
	public BeanDefinitionHolder decorateIfRequired(
			Node node, BeanDefinitionHolder originalDef, @Nullable BeanDefinition containingBd) {

		//获取节点的 namespaceUri
		String namespaceUri = getNamespaceURI(node);
		//如果 namespaceUri 不为 null 且非默认
		if (namespaceUri != null && !isDefaultNamespace(namespaceUri)) {
			//获取命名空间处理器
			NamespaceHandler handler = this.readerContext.getNamespaceHandlerResolver().resolve(namespaceUri);
			//如果 NamespaceHandler 不为 null
			if (handler != null) {
				//处理给定的 originalDef
				BeanDefinitionHolder decorated =
						handler.decorate(node, originalDef, new ParserContext(this.readerContext, this, containingBd));
				//处理结果不为 null
				if (decorated != null) {
					//返回
					return decorated;
				}
			}
			//如果命名空间是以 http://www.springframework.org/schema/ 开始的
			else if (namespaceUri.startsWith("http://www.springframework.org/schema/")) {
				error("Unable to locate Spring NamespaceHandler for XML schema namespace [" + namespaceUri + "]", node);
			}
			else {
				// A custom namespace, not to be handled by Spring - maybe "xml:...".
				if (logger.isDebugEnabled()) {
					logger.debug("No Spring NamespaceHandler found for XML schema namespace [" + namespaceUri + "]");
				}
			}
		}
		return originalDef;
	}

	@Nullable
	private BeanDefinitionHolder parseNestedCustomElement(Element ele, @Nullable BeanDefinition containingBd) {
		BeanDefinition innerDefinition = parseCustomElement(ele, containingBd);
		if (innerDefinition == null) {
			error("Incorrect usage of element '" + ele.getNodeName() + "' in a nested manner. " +
					"This tag cannot be used nested inside <property>.", ele);
			return null;
		}
		String id = ele.getNodeName() + BeanDefinitionReaderUtils.GENERATED_BEAN_NAME_SEPARATOR +
				ObjectUtils.getIdentityHexString(innerDefinition);
		if (logger.isTraceEnabled()) {
			logger.trace("Using generated bean name [" + id +
					"] for nested custom element '" + ele.getNodeName() + "'");
		}
		return new BeanDefinitionHolder(innerDefinition, id);
	}


	/**
	 * Get the namespace URI for the supplied node.
	 * <p>The default implementation uses {@link Node#getNamespaceURI}.
	 * Subclasses may override the default implementation to provide a
	 * different namespace identification mechanism.
	 * @param node the node
	 */
	@Nullable
	public String getNamespaceURI(Node node) {
		return node.getNamespaceURI();
	}

	/**
	 * Get the local name for the supplied {@link Node}.
	 * <p>The default implementation calls {@link Node#getLocalName}.
	 * Subclasses may override the default implementation to provide a
	 * different mechanism for getting the local name.
	 * @param node the {@code Node}
	 */
	public String getLocalName(Node node) {
		return node.getLocalName();
	}

	/**
	 * Determine whether the name of the supplied node is equal to the supplied name.
	 * <p>The default implementation checks the supplied desired name against both
	 * {@link Node#getNodeName()} and {@link Node#getLocalName()}.
	 * <p>Subclasses may override the default implementation to provide a different
	 * mechanism for comparing node names.
	 * @param node the node to compare
	 * @param desiredName the name to check for
	 */
	public boolean nodeNameEquals(Node node, String desiredName) {
		return desiredName.equals(node.getNodeName()) || desiredName.equals(getLocalName(node));
	}

	/**
	 * 确定给定的 URI 是否指向默认的命名空间
	 * Determine whether the given URI indicates the default namespace.
	 */
	public boolean isDefaultNamespace(@Nullable String namespaceUri) {
		//namespaceUri 为空或者为 BEANS_NAMESPACE_URI 都是默认命名空间
		return (!StringUtils.hasLength(namespaceUri) || BEANS_NAMESPACE_URI.equals(namespaceUri));
	}

	/**
	 * 确定给定的 node 节点是否指向默认的命名空间
	 * Determine whether the given node indicates the default namespace.
	 */
	public boolean isDefaultNamespace(Node node) {
		//获取 node的 namespaceURI , 再判断
		return isDefaultNamespace(getNamespaceURI(node));
	}

	/**
	 * 如果为 default 或空串则为默认
	 */
	private boolean isDefaultValue(String value) {
		return (DEFAULT_VALUE.equals(value) || "".equals(value));
	}

	private boolean isCandidateElement(Node node) {
		//node 是 Element 类型, 且 node 指定默认的命名空间或父节点不指向默认的命名空间
		//todo wolfleong 不太懂为什么要这么判断
		return (node instanceof Element && (isDefaultNamespace(node) || !isDefaultNamespace(node.getParentNode())));
	}

}
