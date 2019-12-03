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

package org.springframework.format.support;

import java.lang.annotation.Annotation;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.context.EmbeddedValueResolverAware;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.core.DecoratingProxy;
import org.springframework.core.GenericTypeResolver;
import org.springframework.core.convert.ConversionService;
import org.springframework.core.convert.TypeDescriptor;
import org.springframework.core.convert.converter.ConditionalGenericConverter;
import org.springframework.core.convert.converter.GenericConverter;
import org.springframework.core.convert.support.GenericConversionService;
import org.springframework.format.AnnotationFormatterFactory;
import org.springframework.format.Formatter;
import org.springframework.format.FormatterRegistry;
import org.springframework.format.Parser;
import org.springframework.format.Printer;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;
import org.springframework.util.StringValueResolver;

/**
 * Formatting 的转换服务, 主要 String 和 指定类型的转换
 * - 实现了接口 FormatterRegistry , 可以注册格式化器
 * - 实现了EmbeddedValueResolverAware，所以它还能有非常强大的功能：处理占位
 * A {@link org.springframework.core.convert.ConversionService} implementation
 * designed to be configured as a {@link FormatterRegistry}.
 *
 * @author Keith Donald
 * @author Juergen Hoeller
 * @since 3.0
 */
public class FormattingConversionService extends GenericConversionService
		implements FormatterRegistry, EmbeddedValueResolverAware {

	@Nullable
	private StringValueResolver embeddedValueResolver;

	/**
	 * 注解类型的 printer Converter 的缓存, 主要给 AnnotationPrinterConverter 使用
	 */
	private final Map<AnnotationConverterKey, GenericConverter> cachedPrinters = new ConcurrentHashMap<>(64);

	/**
	 * 注解类型 Parser Converter 的缓存, 主要给 AnnotationParserConverter 使用
	 */
	private final Map<AnnotationConverterKey, GenericConverter> cachedParsers = new ConcurrentHashMap<>(64);


	@Override
	public void setEmbeddedValueResolver(StringValueResolver resolver) {
		this.embeddedValueResolver = resolver;
	}


	@Override
	public void addPrinter(Printer<?> printer) {
		Class<?> fieldType = getFieldType(printer, Printer.class);
		addConverter(new PrinterConverter(fieldType, printer, this));
	}

	@Override
	public void addParser(Parser<?> parser) {
		Class<?> fieldType = getFieldType(parser, Parser.class);
		addConverter(new ParserConverter(fieldType, parser, this));
	}

	/**
	 * 最终也是交给addFormatterForFieldType去做的
	 * @param formatter the formatter to add
	 */
	@Override
	public void addFormatter(Formatter<?> formatter) {
		addFormatterForFieldType(getFieldType(formatter), formatter);
	}

	@Override
	public void addFormatterForFieldType(Class<?> fieldType, Formatter<?> formatter) {
		addConverter(new PrinterConverter(fieldType, formatter, this));
		addConverter(new ParserConverter(fieldType, formatter, this));
	}

	@Override
	public void addFormatterForFieldType(Class<?> fieldType, Printer<?> printer, Parser<?> parser) {
		addConverter(new PrinterConverter(fieldType, printer, this));
		addConverter(new ParserConverter(fieldType, parser, this));
	}

	@Override
	public void addFormatterForFieldAnnotation(AnnotationFormatterFactory<? extends Annotation> annotationFormatterFactory) {
		//获取 AnnotationFormatterFactory 接口上的注解类型
		Class<? extends Annotation> annotationType = getAnnotationType(annotationFormatterFactory);
		//如果 embeddedValueResolver 不为 null 且 annotationFormatterFactory 是 EmbeddedValueResolverAware 实例
		if (this.embeddedValueResolver != null && annotationFormatterFactory instanceof EmbeddedValueResolverAware) {
			//注入实例
			((EmbeddedValueResolverAware) annotationFormatterFactory).setEmbeddedValueResolver(this.embeddedValueResolver);
		}
		//获取处理的类型列表
		Set<Class<?>> fieldTypes = annotationFormatterFactory.getFieldTypes();
		//遍历
		for (Class<?> fieldType : fieldTypes) {
			//添加对象变字符串的 Printer
			addConverter(new AnnotationPrinterConverter(annotationType, annotationFormatterFactory, fieldType));
			//添加字符串变对象的 Parser
			addConverter(new AnnotationParserConverter(annotationType, annotationFormatterFactory, fieldType));
		}
	}


	/**
	 * 获取 Formatter 接口上的泛型类型
	 */
	static Class<?> getFieldType(Formatter<?> formatter) {
		return getFieldType(formatter, Formatter.class);
	}

	private static <T> Class<?> getFieldType(T instance, Class<T> genericInterface) {
		//解析实例的接口泛型参数类型
		Class<?> fieldType = GenericTypeResolver.resolveTypeArgument(instance.getClass(), genericInterface);
		//处理 DecoratingProxy
		if (fieldType == null && instance instanceof DecoratingProxy) {
			fieldType = GenericTypeResolver.resolveTypeArgument(
					((DecoratingProxy) instance).getDecoratedClass(), genericInterface);
		}
		//断言泛型参数不为 null
		Assert.notNull(fieldType, () -> "Unable to extract the parameterized field type from " +
					ClassUtils.getShortName(genericInterface) + " [" + instance.getClass().getName() +
					"]; does the class parameterize the <T> generic type?");
		//返回泛型类型
		return fieldType;
	}

	/**
	 * 获取 AnnotationFormatterFactory 接口上的泛型类型
	 */
	@SuppressWarnings("unchecked")
	static Class<? extends Annotation> getAnnotationType(AnnotationFormatterFactory<? extends Annotation> factory) {
		Class<? extends Annotation> annotationType = (Class<? extends Annotation>)
				GenericTypeResolver.resolveTypeArgument(factory.getClass(), AnnotationFormatterFactory.class);
		if (annotationType == null) {
			throw new IllegalArgumentException("Unable to extract parameterized Annotation type argument from " +
					"AnnotationFormatterFactory [" + factory.getClass().getName() +
					"]; does the factory parameterize the <A extends Annotation> generic type?");
		}
		return annotationType;
	}


	/**
	 * Printer 接口适配 GenericConverter 的适配器
	 */
	private static class PrinterConverter implements GenericConverter {

		/**
		 * 要打印的类型
		 */
		private final Class<?> fieldType;

		/**
		 * 要打印的类型描述
		 */
		private final TypeDescriptor printerObjectType;

		/**
		 * Printer 实例
		 */
		@SuppressWarnings("rawtypes")
		private final Printer printer;

		/**
		 * 转换服务
		 */
		private final ConversionService conversionService;

		public PrinterConverter(Class<?> fieldType, Printer<?> printer, ConversionService conversionService) {
			this.fieldType = fieldType;
			//获取要打节的类型
			this.printerObjectType = TypeDescriptor.valueOf(resolvePrinterObjectType(printer));
			this.printer = printer;
			this.conversionService = conversionService;
		}

		@Override
		public Set<ConvertiblePair> getConvertibleTypes() {
			return Collections.singleton(new ConvertiblePair(this.fieldType, String.class));
		}

		@Override
		@SuppressWarnings("unchecked")
		public Object convert(@Nullable Object source, TypeDescriptor sourceType, TypeDescriptor targetType) {
			//如果源类型不是 Printer 接口要处理的类型
			if (!sourceType.isAssignableTo(this.printerObjectType)) {
				//先调用转换服务将源类型转换成 printerObjectType 类型
				source = this.conversionService.convert(source, sourceType, this.printerObjectType);
			}
			//如果结果是 null, 则返回空串
			if (source == null) {
				return "";
			}
			//调用打印服务
			return this.printer.print(source, LocaleContextHolder.getLocale());
		}

		/**
		 * 获取 Printer 接口的泛型实际类
		 */
		@Nullable
		private Class<?> resolvePrinterObjectType(Printer<?> printer) {
			return GenericTypeResolver.resolveTypeArgument(printer.getClass(), Printer.class);
		}

		@Override
		public String toString() {
			return (this.fieldType.getName() + " -> " + String.class.getName() + " : " + this.printer);
		}
	}


	/**
	 * Parser 接口适配 GenericConverter 的适配器
	 */
	private static class ParserConverter implements GenericConverter {

		private final Class<?> fieldType;

		private final Parser<?> parser;

		private final ConversionService conversionService;

		public ParserConverter(Class<?> fieldType, Parser<?> parser, ConversionService conversionService) {
			this.fieldType = fieldType;
			this.parser = parser;
			this.conversionService = conversionService;
		}

		@Override
		public Set<ConvertiblePair> getConvertibleTypes() {
			return Collections.singleton(new ConvertiblePair(String.class, this.fieldType));
		}

		@Override
		@Nullable
		public Object convert(@Nullable Object source, TypeDescriptor sourceType, TypeDescriptor targetType) {
			//强转字符串
			String text = (String) source;
			//如果文本是空, 则返回 null
			if (!StringUtils.hasText(text)) {
				return null;
			}
			Object result;
			try {
				//调用解析
				result = this.parser.parse(text, LocaleContextHolder.getLocale());
			}
			catch (IllegalArgumentException ex) {
				throw ex;
			}
			catch (Throwable ex) {
				throw new IllegalArgumentException("Parse attempt failed for value [" + text + "]", ex);
			}
			//获取解析的结果类型
			TypeDescriptor resultType = TypeDescriptor.valueOf(result.getClass());
			//如果结果类型和目标类型不一致
			if (!resultType.isAssignableTo(targetType)) {
				//调用转换服务转换处理
				result = this.conversionService.convert(result, resultType, targetType);
			}
			//返回结果
			return result;
		}

		@Override
		public String toString() {
			return (String.class.getName() + " -> " + this.fieldType.getName() + ": " + this.parser);
		}
	}


	/**
	 * 注解 Printer 适配 Converter , 这个是带有匹配条件的
	 * - 如果能找到这个 Converter , 则表明是 fieldType 是配置的类型
	 */
	private class AnnotationPrinterConverter implements ConditionalGenericConverter {

		/**
		 * 注解类型
		 */
		private final Class<? extends Annotation> annotationType;

		/**
		 * 注解格式化工厂
		 */
		@SuppressWarnings("rawtypes")
		private final AnnotationFormatterFactory annotationFormatterFactory;

		/**
		 * 对象类型
		 */
		private final Class<?> fieldType;

		public AnnotationPrinterConverter(Class<? extends Annotation> annotationType,
				AnnotationFormatterFactory<?> annotationFormatterFactory, Class<?> fieldType) {

			this.annotationType = annotationType;
			this.annotationFormatterFactory = annotationFormatterFactory;
			this.fieldType = fieldType;
		}

		@Override
		public Set<ConvertiblePair> getConvertibleTypes() {
			return Collections.singleton(new ConvertiblePair(this.fieldType, String.class));
		}

		@Override
		public boolean matches(TypeDescriptor sourceType, TypeDescriptor targetType) {
			//只要有给定注解的类型都可以
			return sourceType.hasAnnotation(this.annotationType);
		}

		@Override
		@SuppressWarnings("unchecked")
		@Nullable
		public Object convert(@Nullable Object source, TypeDescriptor sourceType, TypeDescriptor targetType) {
			//获取注解
			Annotation ann = sourceType.getAnnotation(this.annotationType);
			//如果注解为 null, 则抛错
			if (ann == null) {
				throw new IllegalStateException(
						"Expected [" + this.annotationType.getName() + "] to be present on " + sourceType);
			}
			//创建缓存的 Key
			AnnotationConverterKey converterKey = new AnnotationConverterKey(ann, sourceType.getObjectType());
			//根据 key 从缓存中获取
			GenericConverter converter = cachedPrinters.get(converterKey);
			//如果缓存中没有
			if (converter == null) {
				//用工厂类获取一个 Printer 实例
				Printer<?> printer = this.annotationFormatterFactory.getPrinter(
						converterKey.getAnnotation(), converterKey.getFieldType());
				//创建一个 PrinterConverter
				converter = new PrinterConverter(this.fieldType, printer, FormattingConversionService.this);
				//添加到缓存中
				cachedPrinters.put(converterKey, converter);
			}
			//用转换器转换
			return converter.convert(source, sourceType, targetType);
		}

		@Override
		public String toString() {
			return ("@" + this.annotationType.getName() + " " + this.fieldType.getName() + " -> " +
					String.class.getName() + ": " + this.annotationFormatterFactory);
		}
	}


	/**
	 * 注解 Parser 适配 Converter , 这个是带有匹配条件的
	 */
	private class AnnotationParserConverter implements ConditionalGenericConverter {

		private final Class<? extends Annotation> annotationType;

		@SuppressWarnings("rawtypes")
		private final AnnotationFormatterFactory annotationFormatterFactory;

		private final Class<?> fieldType;

		public AnnotationParserConverter(Class<? extends Annotation> annotationType,
				AnnotationFormatterFactory<?> annotationFormatterFactory, Class<?> fieldType) {

			this.annotationType = annotationType;
			this.annotationFormatterFactory = annotationFormatterFactory;
			this.fieldType = fieldType;
		}

		@Override
		public Set<ConvertiblePair> getConvertibleTypes() {
			return Collections.singleton(new ConvertiblePair(String.class, this.fieldType));
		}

		@Override
		public boolean matches(TypeDescriptor sourceType, TypeDescriptor targetType) {
			return targetType.hasAnnotation(this.annotationType);
		}

		@Override
		@SuppressWarnings("unchecked")
		@Nullable
		public Object convert(@Nullable Object source, TypeDescriptor sourceType, TypeDescriptor targetType) {
			//获取目标类型上的注解
			Annotation ann = targetType.getAnnotation(this.annotationType);
			//注解实例为 null 的话, 报错
			if (ann == null) {
				throw new IllegalStateException(
						"Expected [" + this.annotationType.getName() + "] to be present on " + targetType);
			}
			//创建缓存的 Key
			AnnotationConverterKey converterKey = new AnnotationConverterKey(ann, targetType.getObjectType());
			//从缓存中获承 converter
			GenericConverter converter = cachedParsers.get(converterKey);
			//如果 converter 为 null
			if (converter == null) {
				//用注解工厂创建一个 Parser
				Parser<?> parser = this.annotationFormatterFactory.getParser(
						converterKey.getAnnotation(), converterKey.getFieldType());
				//创建 ParserConverter
				converter = new ParserConverter(this.fieldType, parser, FormattingConversionService.this);
				//添加到缓存
				cachedParsers.put(converterKey, converter);
			}
			//执行转换操作
			return converter.convert(source, sourceType, targetType);
		}

		@Override
		public String toString() {
			return (String.class.getName() + " -> @" + this.annotationType.getName() + " " +
					this.fieldType.getName() + ": " + this.annotationFormatterFactory);
		}
	}


	/**
	 * 注解类型的 Converter 的缓存的 Key
	 */
	private static class AnnotationConverterKey {

		/**
		 * 注解类型
		 */
		private final Annotation annotation;

		/**
		 * 注解上的类型
		 */
		private final Class<?> fieldType;

		public AnnotationConverterKey(Annotation annotation, Class<?> fieldType) {
			this.annotation = annotation;
			this.fieldType = fieldType;
		}

		public Annotation getAnnotation() {
			return this.annotation;
		}

		public Class<?> getFieldType() {
			return this.fieldType;
		}

		@Override
		public boolean equals(@Nullable Object other) {
			if (this == other) {
				return true;
			}
			if (!(other instanceof AnnotationConverterKey)) {
				return false;
			}
			AnnotationConverterKey otherKey = (AnnotationConverterKey) other;
			return (this.fieldType == otherKey.fieldType && this.annotation.equals(otherKey.annotation));
		}

		@Override
		public int hashCode() {
			return (this.fieldType.hashCode() * 29 + this.annotation.hashCode());
		}
	}

}
