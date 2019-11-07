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

package org.springframework.beans.factory.support;

import java.beans.ConstructorProperties;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.logging.Log;

import org.springframework.beans.BeanMetadataElement;
import org.springframework.beans.BeanWrapper;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.beans.BeansException;
import org.springframework.beans.TypeConverter;
import org.springframework.beans.TypeMismatchException;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.BeanDefinitionStoreException;
import org.springframework.beans.factory.InjectionPoint;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import org.springframework.beans.factory.UnsatisfiedDependencyException;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.beans.factory.config.ConstructorArgumentValues;
import org.springframework.beans.factory.config.ConstructorArgumentValues.ValueHolder;
import org.springframework.beans.factory.config.DependencyDescriptor;
import org.springframework.core.CollectionFactory;
import org.springframework.core.MethodParameter;
import org.springframework.core.NamedThreadLocal;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.MethodInvoker;
import org.springframework.util.ObjectUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StringUtils;

/**
 * Delegate for resolving constructors and factory methods.
 * <p>Performs constructor resolution through argument matching.
 *
 * @author Juergen Hoeller
 * @author Rob Harrop
 * @author Mark Fisher
 * @author Costin Leau
 * @author Sebastien Deleuze
 * @author Sam Brannen
 * @since 2.0
 * @see #autowireConstructor
 * @see #instantiateUsingFactoryMethod
 * @see AbstractAutowireCapableBeanFactory
 */
class ConstructorResolver {

	private static final Object[] EMPTY_ARGS = new Object[0];

	/**
	 * Marker for autowired arguments in a cached argument array, to be later replaced
	 * by a {@linkplain #resolveAutowiredArgument resolved autowired argument}.
	 */
	private static final Object autowiredArgumentMarker = new Object();

	private static final NamedThreadLocal<InjectionPoint> currentInjectionPoint =
			new NamedThreadLocal<>("Current injection point");


	private final AbstractAutowireCapableBeanFactory beanFactory;

	private final Log logger;


	/**
	 * Create a new ConstructorResolver for the given factory and instantiation strategy.
	 * @param beanFactory the BeanFactory to work with
	 */
	public ConstructorResolver(AbstractAutowireCapableBeanFactory beanFactory) {
		this.beanFactory = beanFactory;
		this.logger = beanFactory.getLogger();
	}


	/**
	 * "autowire constructor" (with constructor arguments by type) behavior.
	 * Also applied if explicit constructor argument values are specified,
	 * matching all remaining arguments with beans from the bean factory.
	 * <p>This corresponds to constructor injection: In this mode, a Spring
	 * bean factory is able to host components that expect constructor-based
	 * dependency resolution.
	 * @param beanName the name of the bean
	 * @param mbd the merged bean definition for the bean
	 * @param chosenCtors chosen candidate constructors (or {@code null} if none)
	 * @param explicitArgs argument values passed in programmatically via the getBean method,
	 * or {@code null} if none (-> use constructor argument values from bean definition)
	 * @return a BeanWrapper for the new instance
	 */
	public BeanWrapper autowireConstructor(String beanName, RootBeanDefinition mbd,
			@Nullable Constructor<?>[] chosenCtors, @Nullable Object[] explicitArgs) {

		BeanWrapperImpl bw = new BeanWrapperImpl();
		this.beanFactory.initBeanWrapper(bw);

		Constructor<?> constructorToUse = null;
		ArgumentsHolder argsHolderToUse = null;
		Object[] argsToUse = null;

		if (explicitArgs != null) {
			argsToUse = explicitArgs;
		}
		else {
			Object[] argsToResolve = null;
			synchronized (mbd.constructorArgumentLock) {
				constructorToUse = (Constructor<?>) mbd.resolvedConstructorOrFactoryMethod;
				if (constructorToUse != null && mbd.constructorArgumentsResolved) {
					// Found a cached constructor...
					argsToUse = mbd.resolvedConstructorArguments;
					if (argsToUse == null) {
						argsToResolve = mbd.preparedConstructorArguments;
					}
				}
			}
			if (argsToResolve != null) {
				argsToUse = resolvePreparedArguments(beanName, mbd, bw, constructorToUse, argsToResolve, true);
			}
		}

		if (constructorToUse == null || argsToUse == null) {
			// Take specified constructors, if any.
			Constructor<?>[] candidates = chosenCtors;
			if (candidates == null) {
				Class<?> beanClass = mbd.getBeanClass();
				try {
					candidates = (mbd.isNonPublicAccessAllowed() ?
							beanClass.getDeclaredConstructors() : beanClass.getConstructors());
				}
				catch (Throwable ex) {
					throw new BeanCreationException(mbd.getResourceDescription(), beanName,
							"Resolution of declared constructors on bean Class [" + beanClass.getName() +
							"] from ClassLoader [" + beanClass.getClassLoader() + "] failed", ex);
				}
			}

			if (candidates.length == 1 && explicitArgs == null && !mbd.hasConstructorArgumentValues()) {
				Constructor<?> uniqueCandidate = candidates[0];
				if (uniqueCandidate.getParameterCount() == 0) {
					synchronized (mbd.constructorArgumentLock) {
						mbd.resolvedConstructorOrFactoryMethod = uniqueCandidate;
						mbd.constructorArgumentsResolved = true;
						mbd.resolvedConstructorArguments = EMPTY_ARGS;
					}
					bw.setBeanInstance(instantiate(beanName, mbd, uniqueCandidate, EMPTY_ARGS));
					return bw;
				}
			}

			// Need to resolve the constructor.
			boolean autowiring = (chosenCtors != null ||
					mbd.getResolvedAutowireMode() == AutowireCapableBeanFactory.AUTOWIRE_CONSTRUCTOR);
			ConstructorArgumentValues resolvedValues = null;

			int minNrOfArgs;
			if (explicitArgs != null) {
				minNrOfArgs = explicitArgs.length;
			}
			else {
				ConstructorArgumentValues cargs = mbd.getConstructorArgumentValues();
				resolvedValues = new ConstructorArgumentValues();
				minNrOfArgs = resolveConstructorArguments(beanName, mbd, bw, cargs, resolvedValues);
			}

			AutowireUtils.sortConstructors(candidates);
			int minTypeDiffWeight = Integer.MAX_VALUE;
			Set<Constructor<?>> ambiguousConstructors = null;
			LinkedList<UnsatisfiedDependencyException> causes = null;

			for (Constructor<?> candidate : candidates) {
				Class<?>[] paramTypes = candidate.getParameterTypes();

				if (constructorToUse != null && argsToUse != null && argsToUse.length > paramTypes.length) {
					// Already found greedy constructor that can be satisfied ->
					// do not look any further, there are only less greedy constructors left.
					break;
				}
				if (paramTypes.length < minNrOfArgs) {
					continue;
				}

				ArgumentsHolder argsHolder;
				if (resolvedValues != null) {
					try {
						String[] paramNames = ConstructorPropertiesChecker.evaluate(candidate, paramTypes.length);
						if (paramNames == null) {
							ParameterNameDiscoverer pnd = this.beanFactory.getParameterNameDiscoverer();
							if (pnd != null) {
								paramNames = pnd.getParameterNames(candidate);
							}
						}
						argsHolder = createArgumentArray(beanName, mbd, resolvedValues, bw, paramTypes, paramNames,
								getUserDeclaredConstructor(candidate), autowiring, candidates.length == 1);
					}
					catch (UnsatisfiedDependencyException ex) {
						if (logger.isTraceEnabled()) {
							logger.trace("Ignoring constructor [" + candidate + "] of bean '" + beanName + "': " + ex);
						}
						// Swallow and try next constructor.
						if (causes == null) {
							causes = new LinkedList<>();
						}
						causes.add(ex);
						continue;
					}
				}
				else {
					// Explicit arguments given -> arguments length must match exactly.
					if (paramTypes.length != explicitArgs.length) {
						continue;
					}
					argsHolder = new ArgumentsHolder(explicitArgs);
				}

				int typeDiffWeight = (mbd.isLenientConstructorResolution() ?
						argsHolder.getTypeDifferenceWeight(paramTypes) : argsHolder.getAssignabilityWeight(paramTypes));
				// Choose this constructor if it represents the closest match.
				if (typeDiffWeight < minTypeDiffWeight) {
					constructorToUse = candidate;
					argsHolderToUse = argsHolder;
					argsToUse = argsHolder.arguments;
					minTypeDiffWeight = typeDiffWeight;
					ambiguousConstructors = null;
				}
				else if (constructorToUse != null && typeDiffWeight == minTypeDiffWeight) {
					if (ambiguousConstructors == null) {
						ambiguousConstructors = new LinkedHashSet<>();
						ambiguousConstructors.add(constructorToUse);
					}
					ambiguousConstructors.add(candidate);
				}
			}

			if (constructorToUse == null) {
				if (causes != null) {
					UnsatisfiedDependencyException ex = causes.removeLast();
					for (Exception cause : causes) {
						this.beanFactory.onSuppressedException(cause);
					}
					throw ex;
				}
				throw new BeanCreationException(mbd.getResourceDescription(), beanName,
						"Could not resolve matching constructor " +
						"(hint: specify index/type/name arguments for simple parameters to avoid type ambiguities)");
			}
			else if (ambiguousConstructors != null && !mbd.isLenientConstructorResolution()) {
				throw new BeanCreationException(mbd.getResourceDescription(), beanName,
						"Ambiguous constructor matches found in bean '" + beanName + "' " +
						"(hint: specify index/type/name arguments for simple parameters to avoid type ambiguities): " +
						ambiguousConstructors);
			}

			if (explicitArgs == null && argsHolderToUse != null) {
				argsHolderToUse.storeCache(mbd, constructorToUse);
			}
		}

		Assert.state(argsToUse != null, "Unresolved constructor arguments");
		bw.setBeanInstance(instantiate(beanName, mbd, constructorToUse, argsToUse));
		return bw;
	}

	private Object instantiate(
			String beanName, RootBeanDefinition mbd, Constructor<?> constructorToUse, Object[] argsToUse) {

		try {
			InstantiationStrategy strategy = this.beanFactory.getInstantiationStrategy();
			if (System.getSecurityManager() != null) {
				return AccessController.doPrivileged((PrivilegedAction<Object>) () ->
						strategy.instantiate(mbd, beanName, this.beanFactory, constructorToUse, argsToUse),
						this.beanFactory.getAccessControlContext());
			}
			else {
				return strategy.instantiate(mbd, beanName, this.beanFactory, constructorToUse, argsToUse);
			}
		}
		catch (Throwable ex) {
			throw new BeanCreationException(mbd.getResourceDescription(), beanName,
					"Bean instantiation via constructor failed", ex);
		}
	}

	/**
	 * Resolve the factory method in the specified bean definition, if possible.
	 * {@link RootBeanDefinition#getResolvedFactoryMethod()} can be checked for the result.
	 * @param mbd the bean definition to check
	 */
	public void resolveFactoryMethodIfPossible(RootBeanDefinition mbd) {
		Class<?> factoryClass;
		boolean isStatic;
		if (mbd.getFactoryBeanName() != null) {
			factoryClass = this.beanFactory.getType(mbd.getFactoryBeanName());
			isStatic = false;
		}
		else {
			factoryClass = mbd.getBeanClass();
			isStatic = true;
		}
		Assert.state(factoryClass != null, "Unresolvable factory class");
		factoryClass = ClassUtils.getUserClass(factoryClass);

		Method[] candidates = getCandidateMethods(factoryClass, mbd);
		Method uniqueCandidate = null;
		for (Method candidate : candidates) {
			if (Modifier.isStatic(candidate.getModifiers()) == isStatic && mbd.isFactoryMethod(candidate)) {
				if (uniqueCandidate == null) {
					uniqueCandidate = candidate;
				}
				else if (!Arrays.equals(uniqueCandidate.getParameterTypes(), candidate.getParameterTypes())) {
					uniqueCandidate = null;
					break;
				}
			}
		}
		mbd.factoryMethodToIntrospect = uniqueCandidate;
	}

	/**
	 * 获取工厂类的声明的方法
	 * Retrieve all candidate methods for the given class, considering
	 * the {@link RootBeanDefinition#isNonPublicAccessAllowed()} flag.
	 * Called as the starting point for factory method determination.
	 */
	private Method[] getCandidateMethods(Class<?> factoryClass, RootBeanDefinition mbd) {
		if (System.getSecurityManager() != null) {
			//校验权限
			return AccessController.doPrivileged((PrivilegedAction<Method[]>) () ->
					(mbd.isNonPublicAccessAllowed() ?
						ReflectionUtils.getAllDeclaredMethods(factoryClass) : factoryClass.getMethods()));
		}
		else {
			//如果允许非 public 访问, 则获取 factoryClass 的所有方法, 否则只要所有 public 方法
			return (mbd.isNonPublicAccessAllowed() ?
					ReflectionUtils.getAllDeclaredMethods(factoryClass) : factoryClass.getMethods());
		}
	}

	/**
	 * 用工厂方法初始化对象
	 * Instantiate the bean using a named factory method. The method may be static, if the
	 * bean definition parameter specifies a class, rather than a "factory-bean", or
	 * an instance variable on a factory object itself configured using Dependency Injection.
	 * <p>Implementation requires iterating over the static or instance methods with the
	 * name specified in the RootBeanDefinition (the method may be overloaded) and trying
	 * to match with the parameters. We don't have the types attached to constructor args,
	 * so trial and error is the only way to go here. The explicitArgs array may contain
	 * argument values passed in programmatically via the corresponding getBean method.
	 * @param beanName the name of the bean
	 * @param mbd the merged bean definition for the bean
	 * @param explicitArgs argument values passed in programmatically via the getBean
	 * method, or {@code null} if none (-> use constructor argument values from bean definition)
	 * @return a BeanWrapper for the new instance
	 */
	public BeanWrapper instantiateUsingFactoryMethod(
			String beanName, RootBeanDefinition mbd, @Nullable Object[] explicitArgs) {

		//合建 BeanWrapperImpl 对象
		BeanWrapperImpl bw = new BeanWrapperImpl();
		//初始化 BeanWrapperImpl 对象
		//// 向BeanWrapper对象中添加 ConversionService 对象和属性编辑器 PropertyEditor 对象
		this.beanFactory.initBeanWrapper(bw);

		//获得 factoryBean、factoryClass、isStatic、factoryBeanName 属性
		Object factoryBean;
		Class<?> factoryClass;
		//标记工厂方法是否静态
		boolean isStatic;

		String factoryBeanName = mbd.getFactoryBeanName();
		if (factoryBeanName != null) {
			if (factoryBeanName.equals(beanName)) {
				throw new BeanDefinitionStoreException(mbd.getResourceDescription(), beanName,
						"factory-bean reference points back to the same bean definition");
			}
			//根据 factoryBeanName 获取 factoryBean 对象
			factoryBean = this.beanFactory.getBean(factoryBeanName);
			//如果 mbd 是单例且已经存在单例对象, 则直接报异常
			if (mbd.isSingleton() && this.beanFactory.containsSingleton(beanName)) {
				throw new ImplicitlyAppearedSingletonException();
			}
			//获取 factoryClass
			factoryClass = factoryBean.getClass();
			//设置非静态
			isStatic = false;
		}
		//如果没有 factoryBean 则表明是静态方法
		else {
			//如果没有 beanClass 则报错
			// It's a static factory method on the bean class.
			if (!mbd.hasBeanClass()) {
				throw new BeanDefinitionStoreException(mbd.getResourceDescription(), beanName,
						"bean definition declares neither a bean class nor a factory-bean reference");
			}
			factoryBean = null;
			//获取 beanClass 作为 factoryClass
			factoryClass = mbd.getBeanClass();
			//标记为静态
			isStatic = true;
		}

		//获得 factoryMethodToUse、argsHolderToUse、argsToUse 属性
		Method factoryMethodToUse = null;
		ArgumentsHolder argsHolderToUse = null;
		Object[] argsToUse = null;

		//如果指定了构造参数, 则直接使用
		// 在调用 getBean 方法的时候指定了方法参数
		if (explicitArgs != null) {
			argsToUse = explicitArgs;
		}
		//如果没有指定, 则尝试从配置中解析
		else {
			//待解析的构造参数
			Object[] argsToResolve = null;
			//同步
			synchronized (mbd.constructorArgumentLock) {
				//先从缓存中拿
				//获取缓存中的构造函数或者工厂方法
				factoryMethodToUse = (Method) mbd.resolvedConstructorOrFactoryMethod;
				//如果不为 null 且构造参数已经解析
				if (factoryMethodToUse != null && mbd.constructorArgumentsResolved) {
					//直接获取缓存中已经解析好的构造参数
					// Found a cached factory method...
					argsToUse = mbd.resolvedConstructorArguments;
					//如果没有
					if (argsToUse == null) {
						//则获取配置中的构造参数(原始的, 没解析好的)
						argsToResolve = mbd.preparedConstructorArguments;
					}
				}
			}
			// 缓存中存在,则解析存储在 BeanDefinition 中的参数
			// 如给定方法的构造函数 A(int ,int )，则通过此方法后就会把配置文件中的("1","1")转换为 (1,1)
			// 缓存中的值可能是原始值也有可能是最终值
			//如果待解析的构造参数不为 null
			if (argsToResolve != null) {
				//解析原始参数来使用
				argsToUse = resolvePreparedArguments(beanName, mbd, bw, factoryMethodToUse, argsToResolve, true);
			}
		}

		//如果缓存中没有工厂方法, 也没有对应的参数
		if (factoryMethodToUse == null || argsToUse == null) {
			//获取工厂方法的类全名
			// Need to determine the factory method...
			// Try all methods with this name to see if they match the given arguments.
			factoryClass = ClassUtils.getUserClass(factoryClass);

			//候选的方法
			List<Method> candidateList = null;
			//获取工厂方法是否唯一的缓存, 如果唯一
			if (mbd.isFactoryMethodUnique) {
				//工厂方法为 null
				if (factoryMethodToUse == null) {
					//获取已经解析的工厂方法
					factoryMethodToUse = mbd.getResolvedFactoryMethod();
				}
				//如果已经解析的工厂方法不为 null
				if (factoryMethodToUse != null) {
					//设置当前工厂方法为候选方法
					candidateList = Collections.singletonList(factoryMethodToUse);
				}
			}
			//如果没有找到候选的方法列表
			if (candidateList == null) {
				//初始化候选列表
				candidateList = new ArrayList<>();
				//获取工厂类所有的方法
				Method[] rawCandidates = getCandidateMethods(factoryClass, mbd);
				//遍历
				for (Method candidate : rawCandidates) {
					//isStatic 表示是否静态
					//如果方法的静态修饰符相等且是配置的工厂方法名, 则添加到候选方法中
					if (Modifier.isStatic(candidate.getModifiers()) == isStatic && mbd.isFactoryMethod(candidate)) {
						candidateList.add(candidate);
					}
				}
			}

			//如果候选的方法只有一个, 且没有参数, mbd 配置也没有配置参数值
			if (candidateList.size() == 1 && explicitArgs == null && !mbd.hasConstructorArgumentValues()) {
				//获取这个唯一的参数
				Method uniqueCandidate = candidateList.get(0);
				//如果方法没有参数
				if (uniqueCandidate.getParameterCount() == 0) {
					//缓存
					mbd.factoryMethodToIntrospect = uniqueCandidate;
					//同步处理
					synchronized (mbd.constructorArgumentLock) {
						//缓存工厂方法
						mbd.resolvedConstructorOrFactoryMethod = uniqueCandidate;
						//设置构造参数已经解析
						mbd.constructorArgumentsResolved = true;
						//设置参数为空
						mbd.resolvedConstructorArguments = EMPTY_ARGS;
					}
					//创建 Bean 实例, 并设置到 BeanWrapper 中
					bw.setBeanInstance(instantiate(beanName, mbd, factoryBean, uniqueCandidate, EMPTY_ARGS));
					return bw;
				}
			}

			//候选方法列表变数组
			Method[] candidates = candidateList.toArray(new Method[0]);
			//对候选方法进行排序, 先比较是否是 public 构造函数, 再比较构造器参数个数, 小的在前面
			AutowireUtils.sortFactoryMethods(candidates);

			//用于承载解析后的构造函数参数的值
			ConstructorArgumentValues resolvedValues = null;
			//是否是构造器注入
			boolean autowiring = (mbd.getResolvedAutowireMode() == AutowireCapableBeanFactory.AUTOWIRE_CONSTRUCTOR);
			//一开始最小的比重是 Integer.MAX_VALUE
			int minTypeDiffWeight = Integer.MAX_VALUE;
			//记录有冲空的构造方法
			Set<Method> ambiguousFactoryMethods = null;

			int minNrOfArgs;
			//如果参数不为 null
			if (explicitArgs != null) {
				minNrOfArgs = explicitArgs.length;
			}
			//如果传入参数为空
			else {
				//如果有配置参数
				// We don't have arguments passed in programmatically, so we need to resolve the
				// arguments specified in the constructor arguments held in the bean definition.
				if (mbd.hasConstructorArgumentValues()) {
					//获取配置的构造参数
					ConstructorArgumentValues cargs = mbd.getConstructorArgumentValues();
					//创建 ConstructorArgumentValues 保存已经解析好的参数
					resolvedValues = new ConstructorArgumentValues();
					//解析配置的构造函数参数, 根据配置做一些前置转换
					minNrOfArgs = resolveConstructorArguments(beanName, mbd, bw, cargs, resolvedValues);
				}
				else {
					//设置参数个数 0 个
					minNrOfArgs = 0;
				}
			}

			LinkedList<UnsatisfiedDependencyException> causes = null;

			//遍历所有候选方法
			for (Method candidate : candidates) {
				//获取方法的参数类型
				Class<?>[] paramTypes = candidate.getParameterTypes();

				//如果构造参数类型个数大于或等于参数个数, 为什么可以大于呢, 因为有些参数是可以注入的
				if (paramTypes.length >= minNrOfArgs) {
					//方法参数集合
					ArgumentsHolder argsHolder;

					//如果传入参数不为 null
					if (explicitArgs != null) {
						//显示式给定的参数, 参数长度必须完全匹配
						// Explicit arguments given -> arguments length must match exactly.
						if (paramTypes.length != explicitArgs.length) {
							continue;
						}
						//根据传入参数创建 ArgumentsHolder
						argsHolder = new ArgumentsHolder(explicitArgs);
					}
					//处理配置的参数
					else {
						// Resolved constructor arguments: type conversion and/or autowiring necessary.
						try {
							//参数名列表
							String[] paramNames = null;
							//获取参数名发现器
							ParameterNameDiscoverer pnd = this.beanFactory.getParameterNameDiscoverer();
							//如果 pnd 不为 null
							if (pnd != null) {
								//获取候选方法的所有参数名
								paramNames = pnd.getParameterNames(candidate);
							}
							//在已经解析的构造函数参数值的情况下，创建一个参数持有者 ArgumentsHolder 对象, 这些参数值是来自于配置文件中的
							argsHolder = createArgumentArray(beanName, mbd, resolvedValues, bw,
									paramTypes, paramNames, candidate, autowiring, candidates.length == 1);
						}
						catch (UnsatisfiedDependencyException ex) {
							if (logger.isTraceEnabled()) {
								logger.trace("Ignoring factory method [" + candidate + "] of bean '" + beanName + "': " + ex);
							}
							// Swallow and try next overloaded factory method.
							if (causes == null) {
								causes = new LinkedList<>();
							}
							causes.add(ex);
							continue;
						}
					}

					//isLenientConstructorResolution 判断解析构造函数的时候是否以宽松模式还是严格模式
					// 严格模式：解析构造函数时，必须所有的都需要匹配，否则抛出异常
					// 宽松模式：使用具有"最接近的模式"进行匹配
					// typeDiffWeight：类型差异权重
					int typeDiffWeight = (mbd.isLenientConstructorResolution() ?
							argsHolder.getTypeDifferenceWeight(paramTypes) : argsHolder.getAssignabilityWeight(paramTypes));
					//代表最接近的类型匹配，则选择作为构造函数
					// Choose this factory method if it represents the closest match.
					if (typeDiffWeight < minTypeDiffWeight) {
						factoryMethodToUse = candidate;
						argsHolderToUse = argsHolder;
						argsToUse = argsHolder.arguments;
						minTypeDiffWeight = typeDiffWeight;
						ambiguousFactoryMethods = null;
					}
					//如果具有相同参数数量的方法具有相同的类型差异权重，则收集此类型选项
					//但是，仅在非宽松构造函数解析模式下执行该检查，并显式忽略重写方法（具有相同的参数签名）
					// Find out about ambiguity: In case of the same type difference weight
					// for methods with the same number of parameters, collect such candidates
					// and eventually raise an ambiguity exception.
					// However, only perform that check in non-lenient constructor resolution mode,
					// and explicitly ignore overridden methods (with the same parameter signature).
					else if (factoryMethodToUse != null && typeDiffWeight == minTypeDiffWeight &&
							!mbd.isLenientConstructorResolution() &&
							paramTypes.length == factoryMethodToUse.getParameterCount() &&
							!Arrays.equals(paramTypes, factoryMethodToUse.getParameterTypes())) {
						//找到多个可匹配的方法
						if (ambiguousFactoryMethods == null) {
							ambiguousFactoryMethods = new LinkedHashSet<>();
							ambiguousFactoryMethods.add(factoryMethodToUse);
						}
						ambiguousFactoryMethods.add(candidate);
					}
				}
			}

			//没有可执行的工厂方法, 抛出异常
			if (factoryMethodToUse == null || argsToUse == null) {
				if (causes != null) {
					UnsatisfiedDependencyException ex = causes.removeLast();
					for (Exception cause : causes) {
						this.beanFactory.onSuppressedException(cause);
					}
					throw ex;
				}
				List<String> argTypes = new ArrayList<>(minNrOfArgs);
				if (explicitArgs != null) {
					for (Object arg : explicitArgs) {
						argTypes.add(arg != null ? arg.getClass().getSimpleName() : "null");
					}
				}
				else if (resolvedValues != null) {
					Set<ValueHolder> valueHolders = new LinkedHashSet<>(resolvedValues.getArgumentCount());
					valueHolders.addAll(resolvedValues.getIndexedArgumentValues().values());
					valueHolders.addAll(resolvedValues.getGenericArgumentValues());
					for (ValueHolder value : valueHolders) {
						String argType = (value.getType() != null ? ClassUtils.getShortName(value.getType()) :
								(value.getValue() != null ? value.getValue().getClass().getSimpleName() : "null"));
						argTypes.add(argType);
					}
				}
				String argDesc = StringUtils.collectionToCommaDelimitedString(argTypes);
				throw new BeanCreationException(mbd.getResourceDescription(), beanName,
						"No matching factory method found: " +
						(mbd.getFactoryBeanName() != null ?
							"factory bean '" + mbd.getFactoryBeanName() + "'; " : "") +
						"factory method '" + mbd.getFactoryMethodName() + "(" + argDesc + ")'. " +
						"Check that a method with the specified name " +
						(minNrOfArgs > 0 ? "and arguments " : "") +
						"exists and that it is " +
						(isStatic ? "static" : "non-static") + ".");
			}
			else if (void.class == factoryMethodToUse.getReturnType()) {
				throw new BeanCreationException(mbd.getResourceDescription(), beanName,
						"Invalid factory method '" + mbd.getFactoryMethodName() +
						"': needs to have a non-void return type!");
			}
			else if (ambiguousFactoryMethods != null) {
				throw new BeanCreationException(mbd.getResourceDescription(), beanName,
						"Ambiguous factory method matches found in bean '" + beanName + "' " +
						"(hint: specify index/type/name arguments for simple parameters to avoid type ambiguities): " +
						ambiguousFactoryMethods);
			}

			//如果参数为 null 且 argsHolderToUse 不为 null
			if (explicitArgs == null && argsHolderToUse != null) {
				//将解析的构造函数加入缓存
				mbd.factoryMethodToIntrospect = factoryMethodToUse;
				//缓存构造方法的参数
				argsHolderToUse.storeCache(mbd, factoryMethodToUse);
			}
		}

		//初始化, 并且设置到 bw 中
		bw.setBeanInstance(instantiate(beanName, mbd, factoryBean, factoryMethodToUse, argsToUse));
		return bw;
	}

	private Object instantiate(String beanName, RootBeanDefinition mbd,
			@Nullable Object factoryBean, Method factoryMethod, Object[] args) {

		try {
			if (System.getSecurityManager() != null) {
				return AccessController.doPrivileged((PrivilegedAction<Object>) () ->
						this.beanFactory.getInstantiationStrategy().instantiate(
								mbd, beanName, this.beanFactory, factoryBean, factoryMethod, args),
						this.beanFactory.getAccessControlContext());
			}
			else {
				//进行初始化, 获取实例化策略来进行初始化
				return this.beanFactory.getInstantiationStrategy().instantiate(
						mbd, beanName, this.beanFactory, factoryBean, factoryMethod, args);
			}
		}
		catch (Throwable ex) {
			throw new BeanCreationException(mbd.getResourceDescription(), beanName,
					"Bean instantiation via factory method failed", ex);
		}
	}

	/**
	 * 解析配置的构造参数, 就是根据配置的类型做一些转换, 返回构造参数个数
	 * Resolve the constructor arguments for this bean into the resolvedValues object.
	 * This may involve looking up other beans.
	 * <p>This method is also used for handling invocations of static factory methods.
	 */
	private int resolveConstructorArguments(String beanName, RootBeanDefinition mbd, BeanWrapper bw,
			ConstructorArgumentValues cargs, ConstructorArgumentValues resolvedValues) {

		//获取自定义的类型转换器
		TypeConverter customConverter = this.beanFactory.getCustomTypeConverter();
		//如果没有自定义的类型转换器
		TypeConverter converter = (customConverter != null ? customConverter : bw);
		//创建 BeanDefinitionValueResolver
		BeanDefinitionValueResolver valueResolver =
				new BeanDefinitionValueResolver(this.beanFactory, beanName, mbd, converter);

		//获取最少构造参数个数
		int minNrOfArgs = cargs.getArgumentCount();

		//遍历指定索引的参数列表
		for (Map.Entry<Integer, ConstructorArgumentValues.ValueHolder> entry : cargs.getIndexedArgumentValues().entrySet()) {
			//获取参数索引
			int index = entry.getKey();
			//校验索引
			if (index < 0) {
				throw new BeanCreationException(mbd.getResourceDescription(), beanName,
						"Invalid constructor argument index: " + index);
			}
			//当索引比参数个数大时
			if (index > minNrOfArgs) {
				//参数个数加 1
				minNrOfArgs = index + 1;
			}
			//获取参数值
			ConstructorArgumentValues.ValueHolder valueHolder = entry.getValue();
			//参数值是否已经转换
			if (valueHolder.isConverted()) {
				//添加到已经解析的容器中
				resolvedValues.addIndexedArgumentValue(index, valueHolder);
			}
			//如果没有被转换
			else {
				//转换
				Object resolvedValue =
						valueResolver.resolveValueIfNecessary("constructor argument", valueHolder.getValue());
				//创建 ValueHolder
				ConstructorArgumentValues.ValueHolder resolvedValueHolder =
						new ConstructorArgumentValues.ValueHolder(resolvedValue, valueHolder.getType(), valueHolder.getName());
				//设置 Source
				resolvedValueHolder.setSource(valueHolder);
				//添加到 indexedArgument 中
				resolvedValues.addIndexedArgumentValue(index, resolvedValueHolder);
			}
		}

		//遍历通用参数
		for (ConstructorArgumentValues.ValueHolder valueHolder : cargs.getGenericArgumentValues()) {
			//如果值已经被转换
			if (valueHolder.isConverted()) {
				//直接添加
				resolvedValues.addGenericArgumentValue(valueHolder);
			}
			//如果没有被转换
			else {
				//转换值
				Object resolvedValue =
						valueResolver.resolveValueIfNecessary("constructor argument", valueHolder.getValue());
				//创建 ValueHolder
				ConstructorArgumentValues.ValueHolder resolvedValueHolder = new ConstructorArgumentValues.ValueHolder(
						resolvedValue, valueHolder.getType(), valueHolder.getName());
				//设置 Source
				resolvedValueHolder.setSource(valueHolder);
				//添加
				resolvedValues.addGenericArgumentValue(resolvedValueHolder);
			}
		}

		//返回参数个数
		return minNrOfArgs;
	}

	/**
	 * 根据构造方法参数类型和配置参数来组装构造方法所需要的参数
	 * Create an array of arguments to invoke a constructor or factory method,
	 * given the resolved constructor argument values.
	 */
	private ArgumentsHolder createArgumentArray(
			String beanName, RootBeanDefinition mbd, @Nullable ConstructorArgumentValues resolvedValues,
			BeanWrapper bw, Class<?>[] paramTypes, @Nullable String[] paramNames, Executable executable,
			boolean autowiring, boolean fallback) throws UnsatisfiedDependencyException {

		//获取类型转换器, 有自定义则取自定义, 否则取默认
		TypeConverter customConverter = this.beanFactory.getCustomTypeConverter();
		TypeConverter converter = (customConverter != null ? customConverter : bw);

		//用参数类型个数创建 ArgumentsHolder
		ArgumentsHolder args = new ArgumentsHolder(paramTypes.length);
		//记录已经使用过的 ValueHolder
		Set<ConstructorArgumentValues.ValueHolder> usedValueHolders = new HashSet<>(paramTypes.length);
		//记录需要被依赖注入的 beanName, 也就是构造器注入
		Set<String> autowiredBeanNames = new LinkedHashSet<>(4);

		//遍历参数类型
		for (int paramIndex = 0; paramIndex < paramTypes.length; paramIndex++) {
			//获取指定索引的参数类型
			Class<?> paramType = paramTypes[paramIndex];
			//获取指位置的参数名称, 如果没有则返回空串
			String paramName = (paramNames != null ? paramNames[paramIndex] : "");
			//尝试获取参数值
			// Try to find matching constructor argument value, either indexed or generic.
			ConstructorArgumentValues.ValueHolder valueHolder = null;
			//如果已经解析的参数集合不为 null
			if (resolvedValues != null) {
				//根据参数索引和参数名和参数类型获取 ValueHolder
				valueHolder = resolvedValues.getArgumentValue(paramIndex, paramType, paramName, usedValueHolders);
				// If we couldn't find a direct match and are not supposed to autowire,
				// let's try the next generic, untyped argument value as fallback:
				// it could match after type conversion (for example, String -> int).
				//todo wolfleong 不太懂
				//如果参数 ValueHolder 为 null , 非注入参数或参数类型个数跟已经解析的参数个数一致, 则不用类型和名称获取下一个通用参数
				if (valueHolder == null && (!autowiring || paramTypes.length == resolvedValues.getArgumentCount())) {
					valueHolder = resolvedValues.getGenericArgumentValue(null, null, usedValueHolders);
				}
			}
			//如果没有找到当前位置的 ValueHolder
			if (valueHolder != null) {
				// We found a potential match - let's give it a try.
				// Do not consider the same value definition multiple times!
				//记录已经使用的 ValueHolder
				usedValueHolders.add(valueHolder);
				//获取参数值
				Object originalValue = valueHolder.getValue();
				Object convertedValue;
				//判断参数值是否已经转换
				if (valueHolder.isConverted()) {
					//转换的参数值
					convertedValue = valueHolder.getConvertedValue();
					//缓存起来
					args.preparedArguments[paramIndex] = convertedValue;
				}
				//如果没有转换
				else {
					//创建 MethodParameter
					MethodParameter methodParam = MethodParameter.forExecutable(executable, paramIndex);
					try {
						//转换参数
						convertedValue = converter.convertIfNecessary(originalValue, paramType, methodParam);
					}
					catch (TypeMismatchException ex) {
						throw new UnsatisfiedDependencyException(
								mbd.getResourceDescription(), beanName, new InjectionPoint(methodParam),
								"Could not convert argument value of type [" +
										ObjectUtils.nullSafeClassName(valueHolder.getValue()) +
										"] to required type [" + paramType.getName() + "]: " + ex.getMessage());
					}
					//获取 Source 对象
					Object sourceHolder = valueHolder.getSource();
					//todo wolfleong 不懂为什么要这么处理, 有什么情况下 source 不是 ValueHolder 类型的吗
					//如果是 ValueHolder
					if (sourceHolder instanceof ConstructorArgumentValues.ValueHolder) {
						//获取值
						Object sourceValue = ((ConstructorArgumentValues.ValueHolder) sourceHolder).getValue();
						//设置需要解析
						args.resolveNecessary = true;
						//获取起来
						args.preparedArguments[paramIndex] = sourceValue;
					}
				}
				//缓存参数值
				args.arguments[paramIndex] = convertedValue;
				//缓存原始参数值
				args.rawArguments[paramIndex] = originalValue;
			}
			//如果找不到 ValueHolder 参数值, 则是要注入的
			else {
				//创建 MethodParameter
				MethodParameter methodParam = MethodParameter.forExecutable(executable, paramIndex);
				// No explicit match found: we're either supposed to autowire or
				// have to fail creating an argument array for the given constructor.
				if (!autowiring) {
					throw new UnsatisfiedDependencyException(
							mbd.getResourceDescription(), beanName, new InjectionPoint(methodParam),
							"Ambiguous argument values for parameter of type [" + paramType.getName() +
							"] - did you specify the correct bean references as arguments?");
				}
				try {
					//解析注入的参数
					Object autowiredArgument = resolveAutowiredArgument(
							methodParam, beanName, autowiredBeanNames, converter, fallback);
					//缓存原始参数值
					args.rawArguments[paramIndex] = autowiredArgument;
					//缓存参数
					args.arguments[paramIndex] = autowiredArgument;
					//标记注入
					args.preparedArguments[paramIndex] = autowiredArgumentMarker;
					//标记需要解析
					args.resolveNecessary = true;
				}
				catch (BeansException ex) {
					throw new UnsatisfiedDependencyException(
							mbd.getResourceDescription(), beanName, new InjectionPoint(methodParam), ex);
				}
			}
		}

		//注册依赖的 Bean
		for (String autowiredBeanName : autowiredBeanNames) {
			this.beanFactory.registerDependentBean(autowiredBeanName, beanName);
			if (logger.isDebugEnabled()) {
				logger.debug("Autowiring by type from bean name '" + beanName +
						"' via " + (executable instanceof Constructor ? "constructor" : "factory method") +
						" to bean named '" + autowiredBeanName + "'");
			}
		}

		return args;
	}

	/**
	 * 将缓存中已经整理好的预备参数进行解析并且转成构造方法参数对应的类型
	 * Resolve the prepared arguments stored in the given bean definition.
	 */
	private Object[] resolvePreparedArguments(String beanName, RootBeanDefinition mbd, BeanWrapper bw,
			Executable executable, Object[] argsToResolve, boolean fallback) {

		//获取自定义的 TypeConverter
		TypeConverter customConverter = this.beanFactory.getCustomTypeConverter();
		//如果没有自定义的, 则获取默认的
		TypeConverter converter = (customConverter != null ? customConverter : bw);
		//创建 BeanDefinition 的值解析器
		BeanDefinitionValueResolver valueResolver =
				new BeanDefinitionValueResolver(this.beanFactory, beanName, mbd, converter);
		//获取执行器(构造器或方法)的所有参数类型
		Class<?>[] paramTypes = executable.getParameterTypes();

		//用于缓存解析好的参数
		Object[] resolvedArgs = new Object[argsToResolve.length];
		//遍历参数
		for (int argIndex = 0; argIndex < argsToResolve.length; argIndex++) {
			//获取指定位置的参数值
			Object argValue = argsToResolve[argIndex];
			//创建 MethodParameter
			MethodParameter methodParam = MethodParameter.forExecutable(executable, argIndex);
			//有要注入的标记
			if (argValue == autowiredArgumentMarker) {
				//处理注入的参数
				argValue = resolveAutowiredArgument(methodParam, beanName, null, converter, fallback);
			}
			//如果是 BeanMetadataElement
			else if (argValue instanceof BeanMetadataElement) {
				//用 BeanDefinitionValueResolver 解析
				argValue = valueResolver.resolveValueIfNecessary("constructor argument", argValue);
			}
			//如果是字符串
			else if (argValue instanceof String) {
				//解析字符串
				argValue = this.beanFactory.evaluateBeanDefinitionString((String) argValue, mbd);
			}
			//获取参数的类型
			Class<?> paramType = paramTypes[argIndex];
			try {
				//转换类型
				resolvedArgs[argIndex] = converter.convertIfNecessary(argValue, paramType, methodParam);
			}
			catch (TypeMismatchException ex) {
				throw new UnsatisfiedDependencyException(
						mbd.getResourceDescription(), beanName, new InjectionPoint(methodParam),
						"Could not convert argument value of type [" + ObjectUtils.nullSafeClassName(argValue) +
						"] to required type [" + paramType.getName() + "]: " + ex.getMessage());
			}
		}
		//返回参数列表
		return resolvedArgs;
	}

	protected Constructor<?> getUserDeclaredConstructor(Constructor<?> constructor) {
		Class<?> declaringClass = constructor.getDeclaringClass();
		Class<?> userClass = ClassUtils.getUserClass(declaringClass);
		if (userClass != declaringClass) {
			try {
				return userClass.getDeclaredConstructor(constructor.getParameterTypes());
			}
			catch (NoSuchMethodException ex) {
				// No equivalent constructor on user class (superclass)...
				// Let's proceed with the given constructor as we usually would.
			}
		}
		return constructor;
	}

	/**
	 * Template method for resolving the specified argument which is supposed to be autowired.
	 */
	@Nullable
	protected Object resolveAutowiredArgument(MethodParameter param, String beanName,
			@Nullable Set<String> autowiredBeanNames, TypeConverter typeConverter, boolean fallback) {

		Class<?> paramType = param.getParameterType();
		if (InjectionPoint.class.isAssignableFrom(paramType)) {
			InjectionPoint injectionPoint = currentInjectionPoint.get();
			if (injectionPoint == null) {
				throw new IllegalStateException("No current InjectionPoint available for " + param);
			}
			return injectionPoint;
		}
		try {
			return this.beanFactory.resolveDependency(
					new DependencyDescriptor(param, true), beanName, autowiredBeanNames, typeConverter);
		}
		catch (NoUniqueBeanDefinitionException ex) {
			throw ex;
		}
		catch (NoSuchBeanDefinitionException ex) {
			if (fallback) {
				// Single constructor or factory method -> let's return an empty array/collection
				// for e.g. a vararg or a non-null List/Set/Map parameter.
				if (paramType.isArray()) {
					return Array.newInstance(paramType.getComponentType(), 0);
				}
				else if (CollectionFactory.isApproximableCollectionType(paramType)) {
					return CollectionFactory.createCollection(paramType, 0);
				}
				else if (CollectionFactory.isApproximableMapType(paramType)) {
					return CollectionFactory.createMap(paramType, 0);
				}
			}
			throw ex;
		}
	}

	static InjectionPoint setCurrentInjectionPoint(@Nullable InjectionPoint injectionPoint) {
		InjectionPoint old = currentInjectionPoint.get();
		if (injectionPoint != null) {
			currentInjectionPoint.set(injectionPoint);
		}
		else {
			currentInjectionPoint.remove();
		}
		return old;
	}


	/**
	 * Private inner class for holding argument combinations.
	 */
	private static class ArgumentsHolder {

		public final Object[] rawArguments;

		public final Object[] arguments;

		public final Object[] preparedArguments;

		public boolean resolveNecessary = false;

		public ArgumentsHolder(int size) {
			this.rawArguments = new Object[size];
			this.arguments = new Object[size];
			this.preparedArguments = new Object[size];
		}

		public ArgumentsHolder(Object[] args) {
			this.rawArguments = args;
			this.arguments = args;
			this.preparedArguments = args;
		}

		/**
		 * 获取根据给定的方法参数类型, 计算参数与方法参数类型的匹配度
		 */
		public int getTypeDifferenceWeight(Class<?>[] paramTypes) {
			// If valid arguments found, determine type difference weight.
			// Try type difference weight on both the converted arguments and
			// the raw arguments. If the raw weight is better, use it.
			// Decrease raw weight by 1024 to prefer it over equal converted weight.
			//计算转换后的参数的匹配度
			int typeDiffWeight = MethodInvoker.getTypeDifferenceWeight(paramTypes, this.arguments);
			//计算原参数的匹配度, 为什么要减 1024 呢, 主要是同样匹配上的情况下, 为了更偏向于原参数
			int rawTypeDiffWeight = MethodInvoker.getTypeDifferenceWeight(paramTypes, this.rawArguments) - 1024;
			//最两个匹配度最小的
			return Math.min(rawTypeDiffWeight, typeDiffWeight);
		}

		/**
		 * 获取不严格的匹配度
		 */
		public int getAssignabilityWeight(Class<?>[] paramTypes) {
			//只要已经解析的参数中有一个不匹配方法参数类型的, 直接返回 Integer 最大值
			for (int i = 0; i < paramTypes.length; i++) {
				if (!ClassUtils.isAssignableValue(paramTypes[i], this.arguments[i])) {
					return Integer.MAX_VALUE;
				}
			}
			//只要原参数中有一个不匹配方法参数类型的, 返回 Integer.MAX_VALUE - 512
			for (int i = 0; i < paramTypes.length; i++) {
				if (!ClassUtils.isAssignableValue(paramTypes[i], this.rawArguments[i])) {
					return Integer.MAX_VALUE - 512;
				}
			}
			//默认返回
			return Integer.MAX_VALUE - 1024;
		}

		/**
		 * 将解析后的参数缓存到 RootBeanDefinition 中
		 */
		public void storeCache(RootBeanDefinition mbd, Executable constructorOrFactoryMethod) {
			//参数锁
			synchronized (mbd.constructorArgumentLock) {
				//构造方方法
				mbd.resolvedConstructorOrFactoryMethod = constructorOrFactoryMethod;
				//设置已经解析
				mbd.constructorArgumentsResolved = true;
				//如果参数是需要解析的
				if (this.resolveNecessary) {
					//缓存到 preparedConstructorArguments
					mbd.preparedConstructorArguments = this.preparedArguments;
				}
				else {
					//如果参数不需要解析的, 则缓存到 resolvedConstructorArguments
					mbd.resolvedConstructorArguments = this.arguments;
				}
			}
		}
	}


	/**
	 * Delegate for checking Java 6's {@link ConstructorProperties} annotation.
	 */
	private static class ConstructorPropertiesChecker {

		@Nullable
		public static String[] evaluate(Constructor<?> candidate, int paramCount) {
			ConstructorProperties cp = candidate.getAnnotation(ConstructorProperties.class);
			if (cp != null) {
				String[] names = cp.value();
				if (names.length != paramCount) {
					throw new IllegalStateException("Constructor annotated with @ConstructorProperties but not " +
							"corresponding to actual number of parameters (" + paramCount + "): " + candidate);
				}
				return names;
			}
			else {
				return null;
			}
		}
	}

}
