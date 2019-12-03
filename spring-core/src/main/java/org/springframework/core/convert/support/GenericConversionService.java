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

package org.springframework.core.convert.support;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.core.DecoratingProxy;
import org.springframework.core.ResolvableType;
import org.springframework.core.convert.ConversionException;
import org.springframework.core.convert.ConversionFailedException;
import org.springframework.core.convert.ConversionService;
import org.springframework.core.convert.ConverterNotFoundException;
import org.springframework.core.convert.TypeDescriptor;
import org.springframework.core.convert.converter.ConditionalConverter;
import org.springframework.core.convert.converter.ConditionalGenericConverter;
import org.springframework.core.convert.converter.Converter;
import org.springframework.core.convert.converter.ConverterFactory;
import org.springframework.core.convert.converter.ConverterRegistry;
import org.springframework.core.convert.converter.GenericConverter;
import org.springframework.core.convert.converter.GenericConverter.ConvertiblePair;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.ConcurrentReferenceHashMap;
import org.springframework.util.StringUtils;

/**
 * ConversionService 接口的基础实现，适用于大部分条件下的转换工作，通过 ConfigurableConversionService 接口间接地将 ConverterRegistry 实现为注册 API 。
 * - 这个 GenericConverter 类型转换的设计有点精妙, 不仅仅可以针对指定的类型进行转换, 如果没有的话, 还会尝试父类或接口的转换器进行尝试,
 *   如果通过父类的转换器的话, 可能会进行递归多层转换才得到最后的值
 * - 也就是这个转换服务获得的转换器并不一定能转换到最终的结果, 需要多次转换
 * - 它这个转换多次尝试是交给转换器自己处理, 并不是由 GenericConversionService 处理的, 而且只会处理一个链路, 并不会尝试另一个链路
 * - 感觉其实可以优化一下, 在获取到非精确的转换器之后, 如果转换不成功, 则可以尝试下一组转换链路, 转换链路的尝试由 GenericConversionService 控制
 * Base {@link ConversionService} implementation suitable for use in most environments.
 * Indirectly implements {@link ConverterRegistry} as registration API through the
 * {@link ConfigurableConversionService} interface.
 *
 * @author Keith Donald
 * @author Juergen Hoeller
 * @author Chris Beams
 * @author Phillip Webb
 * @author David Haraburda
 * @since 3.0
 */
public class GenericConversionService implements ConfigurableConversionService {

	/**
	 * 没有操作的转换器, 指 sourceType 直接就是 targetType 类型, 所以不需要转换
	 * General NO-OP converter used when conversion is not required.
	 */
	private static final GenericConverter NO_OP_CONVERTER = new NoOpConverter("NO_OP");

	/**
	 * Used as a cache entry when no converter is available.
	 * This converter is never returned.
	 */
	private static final GenericConverter NO_MATCH = new NoOpConverter("NO_MATCH");


	/**
	 * 所有 Converter 集合的封装对象
	 */
	private final Converters converters = new Converters();

	/**
	 * GenericConverter 的缓存
	 */
	private final Map<ConverterCacheKey, GenericConverter> converterCache = new ConcurrentReferenceHashMap<>(64);


	// ConverterRegistry implementation

	@Override
	public void addConverter(Converter<?, ?> converter) {
		//获取泛型列表
		ResolvableType[] typeInfo = getRequiredTypeInfo(converter.getClass(), Converter.class);
		//如果泛型参数是 null 且 converter 是实现 DecoratingProxy 的
		if (typeInfo == null && converter instanceof DecoratingProxy) {
			typeInfo = getRequiredTypeInfo(((DecoratingProxy) converter).getDecoratedClass(), Converter.class);
		}
		//如果获取不到泛型参数, 则直接报错
		if (typeInfo == null) {
			throw new IllegalArgumentException("Unable to determine source type <S> and target type <T> for your " +
					"Converter [" + converter.getClass().getName() + "]; does the class parameterize those types?");
		}
		//创建 ConverterAdapter , 添加添加到 GenericConverter 集合中
		addConverter(new ConverterAdapter(converter, typeInfo[0], typeInfo[1]));
	}

	@Override
	public <S, T> void addConverter(Class<S> sourceType, Class<T> targetType, Converter<? super S, ? extends T> converter) {
		addConverter(new ConverterAdapter(
				converter, ResolvableType.forClass(sourceType), ResolvableType.forClass(targetType)));
	}

	/**
	 * 添加一个 GenericConverter
	 */
	@Override
	public void addConverter(GenericConverter converter) {
		//添加
		this.converters.add(converter);
		//清空缓存
		invalidateCache();
	}

	@Override
	public void addConverterFactory(ConverterFactory<?, ?> factory) {
		//获取接口泛型
		ResolvableType[] typeInfo = getRequiredTypeInfo(factory.getClass(), ConverterFactory.class);
		//处理代理装饰类
		if (typeInfo == null && factory instanceof DecoratingProxy) {
			typeInfo = getRequiredTypeInfo(((DecoratingProxy) factory).getDecoratedClass(), ConverterFactory.class);
		}
		//没有泛型则报错
		if (typeInfo == null) {
			throw new IllegalArgumentException("Unable to determine source type <S> and target type <T> for your " +
					"ConverterFactory [" + factory.getClass().getName() + "]; does the class parameterize those types?");
		}
		//添加 ConverterFactoryAdapter
		addConverter(new ConverterFactoryAdapter(factory,
				new ConvertiblePair(typeInfo[0].toClass(), typeInfo[1].toClass())));
	}

	@Override
	public void removeConvertible(Class<?> sourceType, Class<?> targetType) {
		this.converters.remove(sourceType, targetType);
		invalidateCache();
	}


	// ConversionService implementation

	@Override
	public boolean canConvert(@Nullable Class<?> sourceType, Class<?> targetType) {
		Assert.notNull(targetType, "Target type to convert to cannot be null");
		return canConvert((sourceType != null ? TypeDescriptor.valueOf(sourceType) : null),
				TypeDescriptor.valueOf(targetType));
	}

	/**
	 * 给定类型, 判断是否可以转换
	 */
	@Override
	public boolean canConvert(@Nullable TypeDescriptor sourceType, TypeDescriptor targetType) {
		Assert.notNull(targetType, "Target type to convert to cannot be null");
		//如果 sourceType 为 null, 则可以转换
		if (sourceType == null) {
			return true;
		}
		//转换器不为 null , 则表示可以转换
		GenericConverter converter = getConverter(sourceType, targetType);
		return (converter != null);
	}

	/**
	 * 是否可以直接转换, 表示不用经过任何操作就能转换
	 * Return whether conversion between the source type and the target type can be bypassed.
	 * <p>More precisely, this method will return true if objects of sourceType can be
	 * converted to the target type by returning the source object unchanged.
	 * @param sourceType context about the source type to convert from
	 * (may be {@code null} if source is {@code null})
	 * @param targetType context about the target type to convert to (required)
	 * @return {@code true} if conversion can be bypassed; {@code false} otherwise
	 * @throws IllegalArgumentException if targetType is {@code null}
	 * @since 3.2
	 */
	public boolean canBypassConvert(@Nullable TypeDescriptor sourceType, TypeDescriptor targetType) {
		//目标类型不为 null
		Assert.notNull(targetType, "Target type to convert to cannot be null");
		//如果 sourceType 是 null, 则表示可以转换
		if (sourceType == null) {
			return true;
		}
		//根据 sourceType 和 targetType 获取转换器
		GenericConverter converter = getConverter(sourceType, targetType);
		//如果是 NO_OP_CONVERTER , 则返回 true
		return (converter == NO_OP_CONVERTER);
	}

	@Override
	@SuppressWarnings("unchecked")
	@Nullable
	public <T> T convert(@Nullable Object source, Class<T> targetType) {
		Assert.notNull(targetType, "Target type to convert to cannot be null");
		return (T) convert(source, TypeDescriptor.forObject(source), TypeDescriptor.valueOf(targetType));
	}

	@Override
	@Nullable
	public Object convert(@Nullable Object source, @Nullable TypeDescriptor sourceType, TypeDescriptor targetType) {
		Assert.notNull(targetType, "Target type to convert to cannot be null");
		//如果 sourceType 为 null
		if (sourceType == null) {
			//那么转换的值 source 必须也为 null
			Assert.isTrue(source == null, "Source must be [null] if source type == [null]");
			//处理结果
			return handleResult(null, targetType, convertNullSource(null, targetType));
		}
		//如果类型不对，抛出 IllegalArgumentException 异常
		if (source != null && !sourceType.getObjectType().isInstance(source)) {
			throw new IllegalArgumentException("Source to convert from must be an instance of [" +
					sourceType + "]; instead it was a [" + source.getClass().getName() + "]");
		}
		//获得对应的 GenericConverter 对象
		GenericConverter converter = getConverter(sourceType, targetType);
		//如果 converter 非空，则进行转换，然后再处理结果
		if (converter != null) {
			//执行转换操作, 为什么要抽取多一个方法出来呢, 主要是异常处理吗
			Object result = ConversionUtils.invokeConverter(converter, source, sourceType, targetType);
			return handleResult(sourceType, targetType, result);
		}
		//处理 converter 为空的情况
		return handleConverterNotFound(source, sourceType, targetType);
	}

	/**
	 * Convenience operation for converting a source object to the specified targetType,
	 * where the target type is a descriptor that provides additional conversion context.
	 * Simply delegates to {@link #convert(Object, TypeDescriptor, TypeDescriptor)} and
	 * encapsulates the construction of the source type descriptor using
	 * {@link TypeDescriptor#forObject(Object)}.
	 * @param source the source object
	 * @param targetType the target type
	 * @return the converted value
	 * @throws ConversionException if a conversion exception occurred
	 * @throws IllegalArgumentException if targetType is {@code null},
	 * or sourceType is {@code null} but source is not {@code null}
	 */
	@Nullable
	public Object convert(@Nullable Object source, TypeDescriptor targetType) {
		return convert(source, TypeDescriptor.forObject(source), targetType);
	}

	@Override
	public String toString() {
		return this.converters.toString();
	}


	// Protected template methods

	/**
	 * 将 null 值, 转换成目标类型
	 * Template method to convert a {@code null} source.
	 * <p>The default implementation returns {@code null} or the Java 8
	 * {@link java.util.Optional#empty()} instance if the target type is
	 * {@code java.util.Optional}. Subclasses may override this to return
	 * custom {@code null} objects for specific target types.
	 * @param sourceType the source type to convert from
	 * @param targetType the target type to convert to
	 * @return the converted null object
	 */
	@Nullable
	protected Object convertNullSource(@Nullable TypeDescriptor sourceType, TypeDescriptor targetType) {
		if (targetType.getObjectType() == Optional.class) {
			return Optional.empty();
		}
		return null;
	}

	/**
	 * Hook method to lookup the converter for a given sourceType/targetType pair.
	 * First queries this ConversionService's converter cache.
	 * On a cache miss, then performs an exhaustive search for a matching converter.
	 * If no converter matches, returns the default converter.
	 * @param sourceType the source type to convert from
	 * @param targetType the target type to convert to
	 * @return the generic converter that will perform the conversion,
	 * or {@code null} if no suitable converter was found
	 * @see #getDefaultConverter(TypeDescriptor, TypeDescriptor)
	 */
	@Nullable
	protected GenericConverter getConverter(TypeDescriptor sourceType, TypeDescriptor targetType) {
		//创建  ConverterCacheKey 对象
		ConverterCacheKey key = new ConverterCacheKey(sourceType, targetType);
		//从 converterCache 缓存中，获得 GenericConverter 对象 converter
		GenericConverter converter = this.converterCache.get(key);
		//如果 converter 不等 null
		if (converter != null) {
			//如果不是 NO_MATCH 则直接返回, 否则返回 null
			return (converter != NO_MATCH ? converter : null);
		}

		// 如果获取不到，则从 converters 中查找
		converter = this.converters.find(sourceType, targetType);
		// 如果查找不到，则获得默认的 Converter 对象
		if (converter == null) {
			converter = getDefaultConverter(sourceType, targetType);
		}

		// 如果找到 converter ，则添加 converter 到 converterCache 中，并返回 converter
		if (converter != null) {
			this.converterCache.put(key, converter);
			return converter;
		}

		// 如果找不到 converter ，则添加 NO_MATCH 占位符到 converterCache 中，并返回 null
		this.converterCache.put(key, NO_MATCH);
		return null;
	}

	/**
	 * Return the default converter if no converter is found for the given sourceType/targetType pair.
	 * <p>Returns a NO_OP Converter if the source type is assignable to the target type.
	 * Returns {@code null} otherwise, indicating no suitable converter could be found.
	 * @param sourceType the source type to convert from
	 * @param targetType the target type to convert to
	 * @return the default generic converter that will perform the conversion
	 */
	@Nullable
	protected GenericConverter getDefaultConverter(TypeDescriptor sourceType, TypeDescriptor targetType) {
		return (sourceType.isAssignableTo(targetType) ? NO_OP_CONVERTER : null);
	}


	// Internal helpers

	/**
	 * 解析转换器的泛型参数列表
	 * @param converterClass 转换器的实际类
	 * @param genericIfc 转换器接口类
	 * @return 转换器的两个类型参数
	 */
	@Nullable
	private ResolvableType[] getRequiredTypeInfo(Class<?> converterClass, Class<?> genericIfc) {
		//获取转换器的 ResolvableType
		ResolvableType resolvableType = ResolvableType.forClass(converterClass).as(genericIfc);
		//获取泛型列表
		ResolvableType[] generics = resolvableType.getGenerics();
		//如果泛型个数小于 2 , 直接返回 null
		if (generics.length < 2) {
			return null;
		}
		//获取 sourceType 类型
		Class<?> sourceType = generics[0].resolve();
		//获取 targetType 类型
		Class<?> targetType = generics[1].resolve();
		//如果 sourceType 和 targetType 任意一个为 null, 则直接返回 null
		if (sourceType == null || targetType == null) {
			return null;
		}
		//返回泛型列表
		return generics;
	}

	private void invalidateCache() {
		this.converterCache.clear();
	}

	@Nullable
	private Object handleConverterNotFound(
			@Nullable Object source, @Nullable TypeDescriptor sourceType, TypeDescriptor targetType) {

		//如果数据源为 null
		if (source == null) {
			//校验目标类型非原始类型
			assertNotPrimitiveTargetType(sourceType, targetType);
			//直接返回 null
			return null;
		}
		//如果 sourceType 为 null 或 sourceType 是 targetType 的子类型
		//且 source 对象直接是 targetType 的类型
		if ((sourceType == null || sourceType.isAssignableTo(targetType)) &&
				targetType.getObjectType().isInstance(source)) {
			//直接返回
			return source;
		}
		//其他情况直接报错
		throw new ConverterNotFoundException(sourceType, targetType);
	}

	@Nullable
	private Object handleResult(@Nullable TypeDescriptor sourceType, TypeDescriptor targetType, @Nullable Object result) {
		//如果结果对象为 null
		if (result == null) {
			//判断非原始类型
			assertNotPrimitiveTargetType(sourceType, targetType);
		}
		//返回结果对象
		return result;
	}

	/**
	 * 判断目标类型非原始类型(int long, double 等八大基本类型), 如果是原始类型, 则直接报错
	 */
	private void assertNotPrimitiveTargetType(@Nullable TypeDescriptor sourceType, TypeDescriptor targetType) {
		if (targetType.isPrimitive()) {
			throw new ConversionFailedException(sourceType, targetType, null,
					new IllegalArgumentException("A null value cannot be assigned to a primitive type"));
		}
	}


	/**
	 * 将 Converter 适配成 GenericConverter
	 * Adapts a {@link Converter} to a {@link GenericConverter}.
	 */
	@SuppressWarnings("unchecked")
	private final class ConverterAdapter implements ConditionalGenericConverter {

		/**
		 * 转换器
		 */
		private final Converter<Object, Object> converter;

		/**
		 * 类型对信息
		 */
		private final ConvertiblePair typeInfo;

		/**
		 * 带泛型的目标类型
		 */
		private final ResolvableType targetType;

		public ConverterAdapter(Converter<?, ?> converter, ResolvableType sourceType, ResolvableType targetType) {
			this.converter = (Converter<Object, Object>) converter;
			this.typeInfo = new ConvertiblePair(sourceType.toClass(), targetType.toClass());
			this.targetType = targetType;
		}

		@Override
		public Set<ConvertiblePair> getConvertibleTypes() {
			return Collections.singleton(this.typeInfo);
		}

		@Override
		public boolean matches(TypeDescriptor sourceType, TypeDescriptor targetType) {
			//如果目标类型不一样, 则返回 false
			// Check raw type first...
			if (this.typeInfo.getTargetType() != targetType.getObjectType()) {
				return false;
			}
			// Full check for complex generic type match required?
			//todo wolfleong 这个判断不太懂
			ResolvableType rt = targetType.getResolvableType();
			if (!(rt.getType() instanceof Class) && !rt.isAssignableFrom(this.targetType) &&
					!this.targetType.hasUnresolvableGenerics()) {
				return false;
			}
			//如果 converter 不是 ConditionalConverter
			//如果 converter 是 ConditionalConverter 类型, 则调用匹配方法来匹配
			return !(this.converter instanceof ConditionalConverter) ||
					((ConditionalConverter) this.converter).matches(sourceType, targetType);
		}

		@Override
		@Nullable
		public Object convert(@Nullable Object source, TypeDescriptor sourceType, TypeDescriptor targetType) {
			//如果要转换的值为 null
			if (source == null) {
				//将 null 转换成目标类型
				return convertNullSource(sourceType, targetType);
			}
			//使用转换器进行转换
			return this.converter.convert(source);
		}

		@Override
		public String toString() {
			return (this.typeInfo + " : " + this.converter);
		}
	}


	/**
	 * 主要将 ConverterFactory 接口实例适配成 GenericConverter
	 * Adapts a {@link ConverterFactory} to a {@link GenericConverter}.
	 */
	@SuppressWarnings("unchecked")
	private final class ConverterFactoryAdapter implements ConditionalGenericConverter {

		private final ConverterFactory<Object, Object> converterFactory;

		private final ConvertiblePair typeInfo;

		public ConverterFactoryAdapter(ConverterFactory<?, ?> converterFactory, ConvertiblePair typeInfo) {
			this.converterFactory = (ConverterFactory<Object, Object>) converterFactory;
			this.typeInfo = typeInfo;
		}

		@Override
		public Set<ConvertiblePair> getConvertibleTypes() {
			return Collections.singleton(this.typeInfo);
		}

		@Override
		public boolean matches(TypeDescriptor sourceType, TypeDescriptor targetType) {
			//记录是否匹配
			boolean matches = true;
			//如果 ConverterFactory 是 ConditionalConverter 类型
			if (this.converterFactory instanceof ConditionalConverter) {
				//则强转, 并且匹配
				matches = ((ConditionalConverter) this.converterFactory).matches(sourceType, targetType);
			}
			//如果可以匹配
			if (matches) {
				//根据目标类型获取 converter
				Converter<?, ?> converter = this.converterFactory.getConverter(targetType.getType());
				//如果是 ConditionalConverter
				if (converter instanceof ConditionalConverter) {
					//强转并且判断是否匹配
					matches = ((ConditionalConverter) converter).matches(sourceType, targetType);
				}
			}
			//返回结果
			return matches;
		}

		@Override
		@Nullable
		public Object convert(@Nullable Object source, TypeDescriptor sourceType, TypeDescriptor targetType) {
			//如果 source 为 null
			if (source == null) {
				//将 null 转换成目标类型
				return convertNullSource(sourceType, targetType);
			}
			//获取转换器进行转换
			return this.converterFactory.getConverter(targetType.getObjectType()).convert(source);
		}

		@Override
		public String toString() {
			return (this.typeInfo + " : " + this.converterFactory);
		}
	}


	/**
	 * 主要封 Converter 的缓存 key , 主要实现 Comparable 接口, 覆盖 equals 和 hashcode 方法
	 * Key for use with the converter cache.
	 */
	private static final class ConverterCacheKey implements Comparable<ConverterCacheKey> {

		/**
		 * 源类型
		 */
		private final TypeDescriptor sourceType;

		/**
		 * 目标类型
		 */
		private final TypeDescriptor targetType;

		public ConverterCacheKey(TypeDescriptor sourceType, TypeDescriptor targetType) {
			this.sourceType = sourceType;
			this.targetType = targetType;
		}

		@Override
		public boolean equals(@Nullable Object other) {
			if (this == other) {
				return true;
			}
			if (!(other instanceof ConverterCacheKey)) {
				return false;
			}
			ConverterCacheKey otherKey = (ConverterCacheKey) other;
			return (this.sourceType.equals(otherKey.sourceType)) &&
					this.targetType.equals(otherKey.targetType);
		}

		@Override
		public int hashCode() {
			return (this.sourceType.hashCode() * 29 + this.targetType.hashCode());
		}

		@Override
		public String toString() {
			return ("ConverterCacheKey [sourceType = " + this.sourceType +
					", targetType = " + this.targetType + "]");
		}

		@Override
		public int compareTo(ConverterCacheKey other) {
			int result = this.sourceType.getResolvableType().toString().compareTo(
					other.sourceType.getResolvableType().toString());
			if (result == 0) {
				result = this.targetType.getResolvableType().toString().compareTo(
						other.targetType.getResolvableType().toString());
			}
			return result;
		}
	}


	/**
	 * Converters 是 GenericConversionService 内部类，用于管理所有注册的转换器
	 * - 一个 ConvertiblePair 类型对
	 * Manages all converters registered with the service.
	 */
	private static class Converters {

		/**
		 * 缓存着所有的 ConditionalConverter 类型的转换器
		 */
		private final Set<GenericConverter> globalConverters = new LinkedHashSet<>();

		/**
		 * 这里主要维护着 ConvertiblePair 对 ConvertersForPair 的缓存, 反向索引
		 * - ConvertersForPair 相当于封装着多个 GenericConverter 的列表
		 * - GenericConverter 相当于一个 Converter 可以转换多个类型对, 正向索引
		 * - 类型对和 Converter 是多对多的关系
		 */
		private final Map<ConvertiblePair, ConvertersForPair> converters = new LinkedHashMap<>(36);

		/**
		 * 添加一个 GenericConverter
		 */
		public void add(GenericConverter converter) {
			//获取 converter 可以转换的类型对
			Set<ConvertiblePair> convertibleTypes = converter.getConvertibleTypes();
			//如果类型对列表为 null
			if (convertibleTypes == null) {
				//只有 ConditionalConverter 类型的类型对列表才为 null
				Assert.state(converter instanceof ConditionalConverter,
						"Only conditional converters may return null convertible types");
				//添加到 globalConverters
				this.globalConverters.add(converter);
			}
			else {
				//遍历所有的类型对, 唯护一个反向索引
				for (ConvertiblePair convertiblePair : convertibleTypes) {
					//根据 convertiblePair 获取对应的 ConvertersForPair
					ConvertersForPair convertersForPair = getMatchableConverters(convertiblePair);
					convertersForPair.add(converter);
				}
			}
		}

		/**
		 * 根据 ConvertiblePair 获取对应的 ConvertersForPair
		 */
		private ConvertersForPair getMatchableConverters(ConvertiblePair convertiblePair) {
			//从缓存中获取
			ConvertersForPair convertersForPair = this.converters.get(convertiblePair);
			//如果 convertersForPair 为 null, 则创建 ConvertersForPair, 且并添加到缓存中
			if (convertersForPair == null) {
				convertersForPair = new ConvertersForPair();
				this.converters.put(convertiblePair, convertersForPair);
			}
			//返回
			return convertersForPair;
		}

		/**
		 * 根据源类型和目标类型删除转换器列表 ConvertersForPair
		 */
		public void remove(Class<?> sourceType, Class<?> targetType) {
			this.converters.remove(new ConvertiblePair(sourceType, targetType));
		}

		/**
		 * 查找给定源和目标类型的GenericConverter。此方法将尝试通过遍历类型的类和接口层次结构来匹配所有可能的转换器
		 * Find a {@link GenericConverter} given a source and target type.
		 * <p>This method will attempt to match all possible converters by working
		 * through the class and interface hierarchy of the types.
		 * @param sourceType the source type
		 * @param targetType the target type
		 * @return a matching {@link GenericConverter}, or {@code null} if none found
		 */
		@Nullable
		public GenericConverter find(TypeDescriptor sourceType, TypeDescriptor targetType) {
			//获取 sourceType 的所有父类和接口
			// Search the full type hierarchy
			List<Class<?>> sourceCandidates = getClassHierarchy(sourceType.getType());
			//获取 targetType 的所有父类和接口
			List<Class<?>> targetCandidates = getClassHierarchy(targetType.getType());
			//迪卡尔积遍历
			for (Class<?> sourceCandidate : sourceCandidates) {
				for (Class<?> targetCandidate : targetCandidates) {
					//创建两个类型对
					ConvertiblePair convertiblePair = new ConvertiblePair(sourceCandidate, targetCandidate);
					//根据给定源类型和目标类型, 获取注册的转换器
					//这里或许可以做一下优化, 如果没有直接的转换器, 则获取父类的转换器来处理, 如果处理不成功, 则可以尝试下一组父类型转换器,
					//直到尝试所有转换链路都没转换才报错
					GenericConverter converter = getRegisteredConverter(sourceType, targetType, convertiblePair);
					//如果能获取到, 则直接返回
					if (converter != null) {
						return converter;
					}
				}
			}
			//默认返回 null
			return null;
		}

		@Nullable
		private GenericConverter getRegisteredConverter(TypeDescriptor sourceType,
				TypeDescriptor targetType, ConvertiblePair convertiblePair) {

			//根据类型对获取 ConvertersForPair
			// Check specifically registered converters
			ConvertersForPair convertersForPair = this.converters.get(convertiblePair);
			//如果 convertersForPair 不为 null
			if (convertersForPair != null) {
				//根据源类型和目标类型
				GenericConverter converter = convertersForPair.getConverter(sourceType, targetType);
				//如果找到就直接返回
				if (converter != null) {
					return converter;
				}
			}
			//如果没找到, 则找全部通用的
			// Check ConditionalConverters for a dynamic match
			for (GenericConverter globalConverter : this.globalConverters) {
				//强转, 并且匹配 sourceType 和 targetType 的话, 直接返回
				if (((ConditionalConverter) globalConverter).matches(sourceType, targetType)) {
					return globalConverter;
				}
			}
			return null;
		}

		/**
		 * 获取给定类型的继承体系, 也就是 type 类的所有父类和接口
		 * 注意: 数组是有继承体系的, 如: Integer[] 是 Number[] 的子类
		 * Returns an ordered class hierarchy for the given type.
		 * @param type the type
		 * @return an ordered list of all classes that the given type extends or implements
		 */
		private List<Class<?>> getClassHierarchy(Class<?> type) {
			//记录类的继承体系
			List<Class<?>> hierarchy = new ArrayList<>(20);
			//记录已经处理过的类, 主要是去重
			Set<Class<?>> visited = new HashSet<>(20);
			//添加当前类自己到继承体系中
			addToClassHierarchy(0, ClassUtils.resolvePrimitiveIfNecessary(type), false, hierarchy, visited);
			//判断给定类型是否是数组
			boolean array = type.isArray();

			int i = 0;
			//遍历继承体系列表
			while (i < hierarchy.size()) {
				//获取一个候选类
				Class<?> candidate = hierarchy.get(i);
				//如果是数组, 则获取数组的 ComponentType, 否则获取类型
				candidate = (array ? candidate.getComponentType() : ClassUtils.resolvePrimitiveIfNecessary(candidate));
				//获取 candidate 的父类
				Class<?> superclass = candidate.getSuperclass();
				//如果父类不为 null, 且不为 Object 且不为 Enum
				//注意, 这里不处理Object 和 枚举类型
				if (superclass != null && superclass != Object.class && superclass != Enum.class) {
					//将 superclass 添加到列表
					addToClassHierarchy(i + 1, candidate.getSuperclass(), array, hierarchy, visited);
				}
				//添加 candidate 的相关接口
				addInterfacesToClassHierarchy(candidate, array, hierarchy, visited);
				i++;
			}

			//如果 type 是枚举
			if (Enum.class.isAssignableFrom(type)) {
				//添加枚举(可能是数组)
				//感觉这两行代码有一行是废话, type 不可能同是是 Enum 类型但又是数组类型, 也就是进入这里, array 永远等于 false
				addToClassHierarchy(hierarchy.size(), Enum.class, array, hierarchy, visited);
				addToClassHierarchy(hierarchy.size(), Enum.class, false, hierarchy, visited);
				//添加枚举相关的接口
				addInterfacesToClassHierarchy(Enum.class, array, hierarchy, visited);
			}

			//添加 Object 类型 , Object[] 也是 Object 的子类
			addToClassHierarchy(hierarchy.size(), Object.class, array, hierarchy, visited);
			addToClassHierarchy(hierarchy.size(), Object.class, false, hierarchy, visited);
			return hierarchy;
		}

		/**
		 * 添加指定类型 type 的所有接口到继承体系中
		 */
		private void addInterfacesToClassHierarchy(Class<?> type, boolean asArray,
				List<Class<?>> hierarchy, Set<Class<?>> visited) {

			for (Class<?> implementedInterface : type.getInterfaces()) {
				addToClassHierarchy(hierarchy.size(), implementedInterface, asArray, hierarchy, visited);
			}
		}

		/**
		 * 添加类的继承体系中
		 * @param index 指定添加的位置
		 * @param type 要添加的类型
		 * @param asArray 是否作为数组
		 * @param hierarchy 继承体系列表
		 * @param visited 已经添加过的列表
		 */
		private void addToClassHierarchy(int index, Class<?> type, boolean asArray,
				List<Class<?>> hierarchy, Set<Class<?>> visited) {

			//如果是数组, 则创建给定类型 type 的数组类型
			if (asArray) {
				type = Array.newInstance(type, 0).getClass();
			}
			//如果能添加, 则添加到继承体系中
			if (visited.add(type)) {
				hierarchy.add(index, type);
			}
		}

		@Override
		public String toString() {
			StringBuilder builder = new StringBuilder();
			builder.append("ConversionService converters =\n");
			for (String converterString : getConverterStrings()) {
				builder.append('\t').append(converterString).append('\n');
			}
			return builder.toString();
		}

		private List<String> getConverterStrings() {
			List<String> converterStrings = new ArrayList<>();
			for (ConvertersForPair convertersForPair : this.converters.values()) {
				converterStrings.add(convertersForPair.toString());
			}
			Collections.sort(converterStrings);
			return converterStrings;
		}
	}


	/**
	 * Manages converters registered with a specific {@link ConvertiblePair}.
	 */
	private static class ConvertersForPair {

		private final LinkedList<GenericConverter> converters = new LinkedList<>();

		/**
		 * 直接往列表前面添加
		 */
		public void add(GenericConverter converter) {
			this.converters.addFirst(converter);
		}

		/**
		 * 根据源类型和目标类型获取 GenericConverter
		 */
		@Nullable
		public GenericConverter getConverter(TypeDescriptor sourceType, TypeDescriptor targetType) {
			//遍历 converters 列表
			for (GenericConverter converter : this.converters) {
				//如果 converter 不是 ConditionalGenericConverter 则直接返回
				//如果 converter 是 ConditionalGenericConverter, 则要匹配 sourceType 和 targetType
				//注意: 为什么非 ConditionalGenericConverter 直接返回呢
				//只有 FormattingConversionService 的 PrinterConverter 和 ParserConverter 没有实现 ConditionalGenericConverter 接口的
				//这里如果获取到的转换器不是给定的类型, 在转换一层后, 再次进行尝试
				if (!(converter instanceof ConditionalGenericConverter) ||
						((ConditionalGenericConverter) converter).matches(sourceType, targetType)) {
					//返回
					return converter;
				}
			}
			//默认返回 nul
			return null;
		}

		@Override
		public String toString() {
			return StringUtils.collectionToCommaDelimitedString(this.converters);
		}
	}


	/**
	 * 内部转换器, 不执行任何操作
	 * Internal converter that performs no operation.
	 */
	private static class NoOpConverter implements GenericConverter {

		private final String name;

		public NoOpConverter(String name) {
			this.name = name;
		}

		@Override
		public Set<ConvertiblePair> getConvertibleTypes() {
			return null;
		}

		@Override
		@Nullable
		public Object convert(@Nullable Object source, TypeDescriptor sourceType, TypeDescriptor targetType) {
			return source;
		}

		@Override
		public String toString() {
			return this.name;
		}
	}

}
