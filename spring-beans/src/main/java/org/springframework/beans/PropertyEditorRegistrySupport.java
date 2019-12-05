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

package org.springframework.beans;

import java.beans.PropertyEditor;
import java.io.File;
import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Currency;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TimeZone;
import java.util.UUID;
import java.util.regex.Pattern;

import org.xml.sax.InputSource;

import org.springframework.beans.propertyeditors.ByteArrayPropertyEditor;
import org.springframework.beans.propertyeditors.CharArrayPropertyEditor;
import org.springframework.beans.propertyeditors.CharacterEditor;
import org.springframework.beans.propertyeditors.CharsetEditor;
import org.springframework.beans.propertyeditors.ClassArrayEditor;
import org.springframework.beans.propertyeditors.ClassEditor;
import org.springframework.beans.propertyeditors.CurrencyEditor;
import org.springframework.beans.propertyeditors.CustomBooleanEditor;
import org.springframework.beans.propertyeditors.CustomCollectionEditor;
import org.springframework.beans.propertyeditors.CustomMapEditor;
import org.springframework.beans.propertyeditors.CustomNumberEditor;
import org.springframework.beans.propertyeditors.FileEditor;
import org.springframework.beans.propertyeditors.InputSourceEditor;
import org.springframework.beans.propertyeditors.InputStreamEditor;
import org.springframework.beans.propertyeditors.LocaleEditor;
import org.springframework.beans.propertyeditors.PathEditor;
import org.springframework.beans.propertyeditors.PatternEditor;
import org.springframework.beans.propertyeditors.PropertiesEditor;
import org.springframework.beans.propertyeditors.ReaderEditor;
import org.springframework.beans.propertyeditors.StringArrayPropertyEditor;
import org.springframework.beans.propertyeditors.TimeZoneEditor;
import org.springframework.beans.propertyeditors.URIEditor;
import org.springframework.beans.propertyeditors.URLEditor;
import org.springframework.beans.propertyeditors.UUIDEditor;
import org.springframework.beans.propertyeditors.ZoneIdEditor;
import org.springframework.core.convert.ConversionService;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourceArrayPropertyEditor;
import org.springframework.lang.Nullable;
import org.springframework.util.ClassUtils;

/**
 * 实现 PropertyEditorRegistry 接口，存储 PropertyEditor 的容器。 该类会注册一些默认的属性编辑器。
 * Base implementation of the {@link PropertyEditorRegistry} interface.
 * Provides management of default editors and custom editors.
 * Mainly serves as base class for {@link BeanWrapperImpl}.
 *
 * @author Juergen Hoeller
 * @author Rob Harrop
 * @since 1.2.6
 * @see java.beans.PropertyEditorManager
 * @see java.beans.PropertyEditorSupport#setAsText
 * @see java.beans.PropertyEditorSupport#setValue
 */
public class PropertyEditorRegistrySupport implements PropertyEditorRegistry {

	/**
	 * 转换服务
	 */
	@Nullable
	private ConversionService conversionService;

	/**
	 * 是否使用默认的 PropertyEditor
	 */
	private boolean defaultEditorsActive = false;

	/**
	 * 基础类型数组的属性编辑器，数组中各元素以逗号分隔
	 */
	private boolean configValueEditorsActive = false;

	/**
	 * 缓存默认的 PropertyEditor
	 */
	@Nullable
	private Map<Class<?>, PropertyEditor> defaultEditors;

	/**
	 * 注册到这里会覆盖默认的PropertyEditor
	 */
	@Nullable
	private Map<Class<?>, PropertyEditor> overriddenDefaultEditors;

	/**
	 * 自定义的 PropertyEditor
	 */
	@Nullable
	private Map<Class<?>, PropertyEditor> customEditors;

	/**
	 * 路径属性的 PropertyEditor，使用 CustomEditorHolder 包装 PropertyEditor
	 */
	@Nullable
	private Map<String, CustomEditorHolder> customEditorsForPath;

	/**
	 * PropertyEditor 缓存，注册自定义 PropertyEditor 时清空，在获取时缓存
	 * - 主要处理一些不能直接根据类型获取自定义 PropertyEditor 的缓存
	 */
	@Nullable
	private Map<Class<?>, PropertyEditor> customEditorCache;


	/**
	 * Specify a Spring 3.0 ConversionService to use for converting
	 * property values, as an alternative to JavaBeans PropertyEditors.
	 */
	public void setConversionService(@Nullable ConversionService conversionService) {
		this.conversionService = conversionService;
	}

	/**
	 * Return the associated ConversionService, if any.
	 */
	@Nullable
	public ConversionService getConversionService() {
		return this.conversionService;
	}


	//---------------------------------------------------------------------
	// Management of default editors
	//---------------------------------------------------------------------

	/**
	 * 激活使用默认属性编辑器的标记位
	 * 只有在调用 getDefaultEditor 才真正会注册 defaultEditors 中
	 * Activate the default editors for this registry instance,
	 * allowing for lazily registering default editors when needed.
	 */
	protected void registerDefaultEditors() {
		this.defaultEditorsActive = true;
	}

	/**
	 * Activate config value editors which are only intended for configuration purposes,
	 * such as {@link org.springframework.beans.propertyeditors.StringArrayPropertyEditor}.
	 * <p>Those editors are not registered by default simply because they are in
	 * general inappropriate for data binding purposes. Of course, you may register
	 * them individually in any case, through {@link #registerCustomEditor}.
	 */
	public void useConfigValueEditors() {
		this.configValueEditorsActive = true;
	}

	/**
	 * Override the default editor for the specified type with the given property editor.
	 * <p>Note that this is different from registering a custom editor in that the editor
	 * semantically still is a default editor. A ConversionService will override such a
	 * default editor, whereas custom editors usually override the ConversionService.
	 * @param requiredType the type of the property
	 * @param propertyEditor the editor to register
	 * @see #registerCustomEditor(Class, PropertyEditor)
	 */
	public void overrideDefaultEditor(Class<?> requiredType, PropertyEditor propertyEditor) {
		if (this.overriddenDefaultEditors == null) {
			this.overriddenDefaultEditors = new HashMap<>();
		}
		this.overriddenDefaultEditors.put(requiredType, propertyEditor);
	}

	/**
	 * 获取默认属性编辑器, 优先使用 overriddenDefaultEditors 中的属性编辑器
	 *
	 * Retrieve the default editor for the given property type, if any.
	 * <p>Lazily registers the default editors, if they are active.
	 * @param requiredType type of the property
	 * @return the default editor, or {@code null} if none found
	 * @see #registerDefaultEditors
	 */
	@Nullable
	public PropertyEditor getDefaultEditor(Class<?> requiredType) {
		//如果没有激活默认属性编辑器
		if (!this.defaultEditorsActive) {
			//直接返回 null
			return null;
		}
		//如果重写的默认属性编辑器缓存不为 null
		if (this.overriddenDefaultEditors != null) {
			//从 overriddenDefaultEditors 中获取
			PropertyEditor editor = this.overriddenDefaultEditors.get(requiredType);
			//如果编辑器不为 null, 则直接返回
			if (editor != null) {
				return editor;
			}
		}
		//如果默认编辑器缓存为 null
		if (this.defaultEditors == null) {
			//初始化默认编辑缓存, 并设置默认编辑器
			createDefaultEditors();
		}
		//从默认编辑器中获取
		return this.defaultEditors.get(requiredType);
	}

	/**
	 * 初始化默认编辑缓存, 并设置默认编辑器
	 * Actually register the default editors for this registry instance.
	 */
	private void createDefaultEditors() {
		this.defaultEditors = new HashMap<>(64);

		// Simple editors, without parameterization capabilities.
		// The JDK does not contain a default editor for any of these target types.
		this.defaultEditors.put(Charset.class, new CharsetEditor());
		this.defaultEditors.put(Class.class, new ClassEditor());
		this.defaultEditors.put(Class[].class, new ClassArrayEditor());
		this.defaultEditors.put(Currency.class, new CurrencyEditor());
		this.defaultEditors.put(File.class, new FileEditor());
		this.defaultEditors.put(InputStream.class, new InputStreamEditor());
		this.defaultEditors.put(InputSource.class, new InputSourceEditor());
		this.defaultEditors.put(Locale.class, new LocaleEditor());
		this.defaultEditors.put(Path.class, new PathEditor());
		this.defaultEditors.put(Pattern.class, new PatternEditor());
		this.defaultEditors.put(Properties.class, new PropertiesEditor());
		this.defaultEditors.put(Reader.class, new ReaderEditor());
		this.defaultEditors.put(Resource[].class, new ResourceArrayPropertyEditor());
		this.defaultEditors.put(TimeZone.class, new TimeZoneEditor());
		this.defaultEditors.put(URI.class, new URIEditor());
		this.defaultEditors.put(URL.class, new URLEditor());
		this.defaultEditors.put(UUID.class, new UUIDEditor());
		this.defaultEditors.put(ZoneId.class, new ZoneIdEditor());

		// Default instances of collection editors.
		// Can be overridden by registering custom instances of those as custom editors.
		this.defaultEditors.put(Collection.class, new CustomCollectionEditor(Collection.class));
		this.defaultEditors.put(Set.class, new CustomCollectionEditor(Set.class));
		this.defaultEditors.put(SortedSet.class, new CustomCollectionEditor(SortedSet.class));
		this.defaultEditors.put(List.class, new CustomCollectionEditor(List.class));
		this.defaultEditors.put(SortedMap.class, new CustomMapEditor(SortedMap.class));

		// Default editors for primitive arrays.
		this.defaultEditors.put(byte[].class, new ByteArrayPropertyEditor());
		this.defaultEditors.put(char[].class, new CharArrayPropertyEditor());

		// The JDK does not contain a default editor for char!
		this.defaultEditors.put(char.class, new CharacterEditor(false));
		this.defaultEditors.put(Character.class, new CharacterEditor(true));

		// Spring's CustomBooleanEditor accepts more flag values than the JDK's default editor.
		this.defaultEditors.put(boolean.class, new CustomBooleanEditor(false));
		this.defaultEditors.put(Boolean.class, new CustomBooleanEditor(true));

		// The JDK does not contain default editors for number wrapper types!
		// Override JDK primitive number editors with our own CustomNumberEditor.
		this.defaultEditors.put(byte.class, new CustomNumberEditor(Byte.class, false));
		this.defaultEditors.put(Byte.class, new CustomNumberEditor(Byte.class, true));
		this.defaultEditors.put(short.class, new CustomNumberEditor(Short.class, false));
		this.defaultEditors.put(Short.class, new CustomNumberEditor(Short.class, true));
		this.defaultEditors.put(int.class, new CustomNumberEditor(Integer.class, false));
		this.defaultEditors.put(Integer.class, new CustomNumberEditor(Integer.class, true));
		this.defaultEditors.put(long.class, new CustomNumberEditor(Long.class, false));
		this.defaultEditors.put(Long.class, new CustomNumberEditor(Long.class, true));
		this.defaultEditors.put(float.class, new CustomNumberEditor(Float.class, false));
		this.defaultEditors.put(Float.class, new CustomNumberEditor(Float.class, true));
		this.defaultEditors.put(double.class, new CustomNumberEditor(Double.class, false));
		this.defaultEditors.put(Double.class, new CustomNumberEditor(Double.class, true));
		this.defaultEditors.put(BigDecimal.class, new CustomNumberEditor(BigDecimal.class, true));
		this.defaultEditors.put(BigInteger.class, new CustomNumberEditor(BigInteger.class, true));

		// Only register config value editors if explicitly requested.
		if (this.configValueEditorsActive) {
			StringArrayPropertyEditor sae = new StringArrayPropertyEditor();
			this.defaultEditors.put(String[].class, sae);
			this.defaultEditors.put(short[].class, sae);
			this.defaultEditors.put(int[].class, sae);
			this.defaultEditors.put(long[].class, sae);
		}
	}

	/**
	 * 将此实例中的注册的默认编辑器复制到给定的注册中心
	 * Copy the default editors registered in this instance to the given target registry.
	 * @param target the target registry to copy to
	 */
	protected void copyDefaultEditorsTo(PropertyEditorRegistrySupport target) {
		target.defaultEditorsActive = this.defaultEditorsActive;
		target.configValueEditorsActive = this.configValueEditorsActive;
		target.defaultEditors = this.defaultEditors;
		target.overriddenDefaultEditors = this.overriddenDefaultEditors;
	}


	//---------------------------------------------------------------------
	// Management of custom editors
	//---------------------------------------------------------------------

	@Override
	public void registerCustomEditor(Class<?> requiredType, PropertyEditor propertyEditor) {
		//根据类型注册
		registerCustomEditor(requiredType, null, propertyEditor);
	}

	/**
	 * 通用的注册方法，可以注册普通类型的属性编辑器，也可以注册该类型中属性路径的属性编辑器
	 */
	@Override
	public void registerCustomEditor(@Nullable Class<?> requiredType, @Nullable String propertyPath, PropertyEditor propertyEditor) {
		//如果类型为 null 或 propertyPath 为 null, 则报错
		if (requiredType == null && propertyPath == null) {
			throw new IllegalArgumentException("Either requiredType or propertyPath is required");
		}
		//如果 propertyPath 不为 null
		if (propertyPath != null) {
			//如果缓存 customEditorsForPath 没初始化, 则初始化
			if (this.customEditorsForPath == null) {
				this.customEditorsForPath = new LinkedHashMap<>(16);
			}
			//以 propertyPath 为 key, 添加到缓存
			this.customEditorsForPath.put(propertyPath, new CustomEditorHolder(propertyEditor, requiredType));
		}
		else {
			//如果 customEditors 没初始化, 则初始化
			if (this.customEditors == null) {
				this.customEditors = new LinkedHashMap<>(16);
			}
			//添加 propertyEditor 到缓存
			this.customEditors.put(requiredType, propertyEditor);
			//置空 customEditorCache
			this.customEditorCache = null;
		}
	}

	/**
	 * 查询定义的属性编辑器, 有保能属性编辑器的类型是 requiredType 的父类
	 */
	@Override
	@Nullable
	public PropertyEditor findCustomEditor(@Nullable Class<?> requiredType, @Nullable String propertyPath) {
		//记录属性值的类型
		Class<?> requiredTypeToUse = requiredType;
		//如果 propertyPath 不为 null
		if (propertyPath != null) {
			//如果 customEditorsForPath 不为 null
			if (this.customEditorsForPath != null) {
				//从自定义缓存中获取
				// Check property-specific editor first.
				PropertyEditor editor = getCustomEditor(propertyPath, requiredType);
				//属性编辑器为 null
				if (editor == null) {
					List<String> strippedPaths = new ArrayList<>();
					addStrippedPropertyPaths(strippedPaths, "", propertyPath);
					//遍历 strippedPaths , 每一个尝试获取属性编辑器
					for (Iterator<String> it = strippedPaths.iterator(); it.hasNext() && editor == null;) {
						String strippedPath = it.next();
						editor = getCustomEditor(strippedPath, requiredType);
					}
				}
				//如果属性编辑器不为 null, 则直接返回
				if (editor != null) {
					return editor;
				}
			}
			//如果给定类型为 null
			if (requiredType == null) {
				//获取属性值的类型
				requiredTypeToUse = getPropertyType(propertyPath);
			}
		}
		//获取自定义的类型属性编辑器
		// No property-specific editor -> check type-specific editor.
		return getCustomEditor(requiredTypeToUse);
	}

	/**
	 * 根据字段类型或属性 path , 判断属性编辑器是否存在
	 * Determine whether this registry contains a custom editor
	 * for the specified array/collection element.
	 * @param elementType the target type of the element
	 * (can be {@code null} if not known)
	 * @param propertyPath the property path (typically of the array/collection;
	 * can be {@code null} if not known)
	 * @return whether a matching custom editor has been found
	 */
	public boolean hasCustomEditorForElement(@Nullable Class<?> elementType, @Nullable String propertyPath) {
		//如果 propertyPath 不为 null 且 属性path 属性编辑器缓存不为 null
		if (propertyPath != null && this.customEditorsForPath != null) {
			//遍历缓存
			for (Map.Entry<String, CustomEditorHolder> entry : this.customEditorsForPath.entrySet()) {
				//如果属性 path 能匹配, 且根据类型能获取属性编辑器, 则返回 true
				if (PropertyAccessorUtils.matchesProperty(entry.getKey(), propertyPath) &&
						entry.getValue().getPropertyEditor(elementType) != null) {
					return true;
				}
			}
		}
		//前面根据路径获取不到, 则根据 path 获取
		// No property-specific editor -> check type-specific editor.
		return (elementType != null && this.customEditors != null && this.customEditors.containsKey(elementType));
	}

	/**
	 * Determine the property type for the given property path.
	 * <p>Called by {@link #findCustomEditor} if no required type has been specified,
	 * to be able to find a type-specific editor even if just given a property path.
	 * <p>The default implementation always returns {@code null}.
	 * BeanWrapperImpl overrides this with the standard {@code getPropertyType}
	 * method as defined by the BeanWrapper interface.
	 * @param propertyPath the property path to determine the type for
	 * @return the type of the property, or {@code null} if not determinable
	 * @see BeanWrapper#getPropertyType(String)
	 */
	@Nullable
	protected Class<?> getPropertyType(String propertyPath) {
		return null;
	}

	/**
	 * 获取类属性的 PropertyEditor
	 * Get custom editor that has been registered for the given property.
	 * @param propertyName the property path to look for
	 * @param requiredType the type to look for
	 * @return the custom editor, or {@code null} if none specific for this property
	 */
	@Nullable
	private PropertyEditor getCustomEditor(String propertyName, @Nullable Class<?> requiredType) {
		//如果 customEditorsForPath 不为 null, 则根据属性名从 customEditorsForPath 获取属性编辑器 CustomEditorHolder , 否则返回 null
		CustomEditorHolder holder =
				(this.customEditorsForPath != null ? this.customEditorsForPath.get(propertyName) : null);
		//如果 holder 不为 null, 用根据 requiredType 从 holder 中获取属性编辑器
		return (holder != null ? holder.getPropertyEditor(requiredType) : null);
	}

	/**
	 * Get custom editor for the given type. If no direct match found,
	 * try custom editor for superclass (which will in any case be able
	 * to render a value as String via {@code getAsText}).
	 * @param requiredType the type to look for
	 * @return the custom editor, or {@code null} if none found for this type
	 * @see java.beans.PropertyEditor#getAsText()
	 */
	@Nullable
	private PropertyEditor getCustomEditor(@Nullable Class<?> requiredType) {
		//如果给定类型为 null 或 customEditors 缓存为 null
		if (requiredType == null || this.customEditors == null) {
			//直接返回 null
			return null;
		}
		//根据类型获取属性编辑器
		// Check directly registered editor for type.
		PropertyEditor editor = this.customEditors.get(requiredType);
		//如果编辑器为 null
		if (editor == null) {
			//如果 customEditorCache 不为 null
			// Check cached editor for type, registered for superclass or interface.
			if (this.customEditorCache != null) {
				//从 customEditorCache 获取
				editor = this.customEditorCache.get(requiredType);
			}
			//如果还是没找到 editor
			if (editor == null) {
				//遍历所有自定义 customEditors 缓存
				// Find editor for superclass or interface.
				for (Iterator<Class<?>> it = this.customEditors.keySet().iterator(); it.hasNext() && editor == null;) {
					//获取属性编辑器对应的类
					Class<?> key = it.next();
					// requiredType 是否是 key 的子类, 如果是
					if (key.isAssignableFrom(requiredType)) {
						//获取 key 对应的属性编辑器
						editor = this.customEditors.get(key);
						//如果缓存没初始化,则初始化处理
						// Cache editor for search type, to avoid the overhead
						// of repeated assignable-from checks.
						if (this.customEditorCache == null) {
							this.customEditorCache = new HashMap<>();
						}
						//添加到缓存
						this.customEditorCache.put(requiredType, editor);
					}
				}
			}
		}
		//返回最终结果
		return editor;
	}

	/**
	 * 根据字段名获取字段的属性值
	 * Guess the property type of the specified property from the registered
	 * custom editors (provided that they were registered for a specific type).
	 * @param propertyName the name of the property
	 * @return the property type, or {@code null} if not determinable
	 */
	@Nullable
	protected Class<?> guessPropertyTypeFromEditors(String propertyName) {
		//如路径属性的缓存不为 null
		if (this.customEditorsForPath != null) {
			//根据属性名获取缓存 Holder
			CustomEditorHolder editorHolder = this.customEditorsForPath.get(propertyName);
			//如果 editorHolder 不为 null
			if (editorHolder == null) {
				//切割后的属性
				List<String> strippedPaths = new ArrayList<>();
				//处理 [] 的情况
				addStrippedPropertyPaths(strippedPaths, "", propertyName);
				//遍历, 逐个属性获取 editorHolder
				for (Iterator<String> it = strippedPaths.iterator(); it.hasNext() && editorHolder == null;) {
					String strippedName = it.next();
					editorHolder = this.customEditorsForPath.get(strippedName);
				}
			}
			//如果 editorHolder 不为 null
			if (editorHolder != null) {
				//获取注册的属性值
				return editorHolder.getRegisteredType();
			}
		}
		//默认返回 null
		return null;
	}

	/**
	 * 从当前的属性注册器复制自定义属性编辑器到给定的PropertyEditorRegistry
	 * Copy the custom editors registered in this instance to the given target registry.
	 * @param target the target registry to copy to
	 * @param nestedProperty the nested property path of the target registry, if any.
	 * If this is non-null, only editors registered for a path below this nested property
	 * will be copied. If this is null, all editors will be copied.
	 */
	protected void copyCustomEditorsTo(PropertyEditorRegistry target, @Nullable String nestedProperty) {
		//获取真正的属性, 如果 nestedProperty 包括 [] , 则返回 [] 前面的属性, 否则返回原来的 nestedProperty
		String actualPropertyName =
				(nestedProperty != null ? PropertyAccessorUtils.getPropertyName(nestedProperty) : null);
		//如果 customEditors 不为 null
		if (this.customEditors != null) {
			//注册所有自定义属性编辑器
			this.customEditors.forEach(target::registerCustomEditor);
		}
		//如果 customEditorsForPath 不为 null
		if (this.customEditorsForPath != null) {
			//遍历
			this.customEditorsForPath.forEach((editorPath, editorHolder) -> {
				//如果 nestedProperty 不为 null, 复制嵌套属性下的
				if (nestedProperty != null) {
					//获取第一个属性分割符的位置
					int pos = PropertyAccessorUtils.getFirstNestedPropertySeparatorIndex(editorPath);
					//如果属性分割符存在
					if (pos != -1) {
						//获取第一层属性, 如 person.name 中的 person
						String editorNestedProperty = editorPath.substring(0, pos);
						//获取第一层属性后面的属性, 如: person.name 中的 name
						String editorNestedPath = editorPath.substring(pos + 1);
						//如果编辑器的 propertyPath 的第一层属性等于 nestedProperty 或等于 actualPropertyName , 则复制注册器
						if (editorNestedProperty.equals(nestedProperty) || editorNestedProperty.equals(actualPropertyName)) {
							target.registerCustomEditor(
									editorHolder.getRegisteredType(), editorNestedPath, editorHolder.getPropertyEditor());
						}
					}
				}
				else {
					//nestedProperty 为 null, 直接复制所有的编辑器
					target.registerCustomEditor(
							editorHolder.getRegisteredType(), editorPath, editorHolder.getPropertyEditor());
				}
			});
		}
	}


	/**
	 * 处理给定的 propertyPath , 如:
	 * => list[0].age => [list.age]
	 * => list[0].map[name].age => [list.map[name].age, list.map[name].age]
	 * Add property paths with all variations of stripped keys and/or indexes.
	 * Invokes itself recursively with nested paths.
	 * @param strippedPaths the result list to add to
	 * @param nestedPath the current nested path
	 * @param propertyPath the property path to check for keys/indexes to strip
	 */
	private void addStrippedPropertyPaths(List<String> strippedPaths, String nestedPath, String propertyPath) {
		//获取 propertyPath 中 '[' 的开始索引
		int startIndex = propertyPath.indexOf(PropertyAccessor.PROPERTY_KEY_PREFIX_CHAR);
		//如果前缀索引不为空
		if (startIndex != -1) {
			//获取后缀索引
			int endIndex = propertyPath.indexOf(PropertyAccessor.PROPERTY_KEY_SUFFIX_CHAR);
			//如果后缀索引不为空, 以 personMap[0].age
			if (endIndex != -1) {
				// '[' 前面的字符串
				String prefix = propertyPath.substring(0, startIndex);
				// [key]
				String key = propertyPath.substring(startIndex, endIndex + 1);
				//']' 后面的字符串
				String suffix = propertyPath.substring(endIndex + 1, propertyPath.length());
				//将去掉 [0] 后的字符串拼接在一起
				// Strip the first key.
				strippedPaths.add(nestedPath + prefix + suffix);
				//然后继续切割剩下的 suffix
				// Search for further keys to strip, with the first key stripped.
				addStrippedPropertyPaths(strippedPaths, nestedPath + prefix, suffix);
				//不切割第一个, 切割 suffix 的
				// Search for further keys to strip, with the first key not stripped.
				addStrippedPropertyPaths(strippedPaths, nestedPath + prefix + key, suffix);
			}
		}
	}


	/**
	 *  PropertyEditor 和 处理类型的封装类
	 * Holder for a registered custom editor with property name.
	 * Keeps the PropertyEditor itself plus the type it was registered for.
	 */
	private static final class CustomEditorHolder {

		private final PropertyEditor propertyEditor;

		@Nullable
		private final Class<?> registeredType;

		private CustomEditorHolder(PropertyEditor propertyEditor, @Nullable Class<?> registeredType) {
			this.propertyEditor = propertyEditor;
			this.registeredType = registeredType;
		}

		private PropertyEditor getPropertyEditor() {
			return this.propertyEditor;
		}

		@Nullable
		private Class<?> getRegisteredType() {
			return this.registeredType;
		}

		@Nullable
		private PropertyEditor getPropertyEditor(@Nullable Class<?> requiredType) {
			// Special case: If no required type specified, which usually only happens for
			// Collection elements, or required type is not assignable to registered type,
			// which usually only happens for generic properties of type Object -
			// then return PropertyEditor if not registered for Collection or array type.
			// (If not registered for Collection or array, it is assumed to be intended
			// for elements.)
			// 三种情况
			// 1 registeredType 为 null
			// 2 传入的 requiredType 不为 null 且 registeredType 与 requiredType 是任意继承关系
			// 3 传入的 requiredType 为 null 且 registeredType 非集合非数组
			if (this.registeredType == null ||
					(requiredType != null &&
					(ClassUtils.isAssignable(this.registeredType, requiredType) ||
					ClassUtils.isAssignable(requiredType, this.registeredType))) ||
					(requiredType == null &&
					(!Collection.class.isAssignableFrom(this.registeredType) && !this.registeredType.isArray()))) {
				return this.propertyEditor;
			}
			else {
				return null;
			}
		}
	}

}
