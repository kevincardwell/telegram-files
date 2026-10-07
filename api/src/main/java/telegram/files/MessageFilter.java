package telegram.files;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.date.DateTime;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.map.MapUtil;
import cn.hutool.core.util.*;
import cn.hutool.log.Log;
import cn.hutool.log.LogFactory;
import org.apache.commons.jexl3.JexlBuilder;
import org.apache.commons.jexl3.JexlEngine;
import org.apache.commons.jexl3.JexlExpression;
import org.apache.commons.jexl3.MapContext;
import org.apache.commons.jexl3.introspection.JexlPermissions;
import org.drinkless.tdlib.TdApi;
import telegram.files.repository.FileRecord;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class MessageFilter {

    private static final Log log = LogFactory.get();

    private static final CopyOptions BEAN_TO_MAP_OPTIONS_1 = CopyOptions.create()
            .setIgnoreNullValue(true)
            .setFieldValueEditor((_, fieldValue) -> {
                if (fieldValue == null) {
                    return null;
                }
                // For TdApi.Object types, we can convert them to Map recursively
                if (fieldValue instanceof TdApi.Object) {
                    return BeanUtil.beanToMap(fieldValue);
                }
                return fieldValue;
            });

    private static final CopyOptions BEAN_TO_MAP_OPTIONS_2 = CopyOptions.create()
            .setIgnoreNullValue(true)
            .setFieldValueEditor((_, fieldValue) -> {
                if (fieldValue == null) {
                    return null;
                }
                // For TdApi.Object types, we can convert them to Map recursively
                if (fieldValue instanceof TdApi.Object) {
                    return BeanUtil.beanToMap(fieldValue, new LinkedHashMap<>(16, 1), BEAN_TO_MAP_OPTIONS_1);
                }
                return fieldValue;
            });

    private static final CopyOptions BEAN_TO_MAP_OPTIONS_3 = CopyOptions.create()
            .setIgnoreNullValue(true)
            .setFieldValueEditor((_, fieldValue) -> {
                if (fieldValue == null) {
                    return null;
                }
                // For TdApi.Object types, we can convert them to Map recursively
                if (fieldValue instanceof TdApi.Object) {
                    return BeanUtil.beanToMap(fieldValue, new LinkedHashMap<>(16, 1), BEAN_TO_MAP_OPTIONS_2);
                }
                return fieldValue;
            });

    private static final CopyOptions BEAN_TO_MAP_OPTIONS_4 = CopyOptions.create()
            .setIgnoreNullValue(true)
            .setFieldValueEditor((_, fieldValue) -> {
                if (fieldValue == null) {
                    return null;
                }
                // For TdApi.Object types, we can convert them to Map recursively
                if (fieldValue instanceof TdApi.Object) {
                    return BeanUtil.beanToMap(fieldValue, new LinkedHashMap<>(16, 1), BEAN_TO_MAP_OPTIONS_3);
                }
                return fieldValue;
            });

    private static final CopyOptions BEAN_TO_MAP_OPTIONS = CopyOptions.create()
            .setIgnoreNullValue(true)
            .setFieldValueEditor((_, fieldValue) -> {
                if (fieldValue == null) {
                    return null;
                }
                // For TdApi.Object types, we can convert them to Map recursively
                if (fieldValue instanceof TdApi.Object) {
                    return BeanUtil.beanToMap(fieldValue, new LinkedHashMap<>(16, 1), BEAN_TO_MAP_OPTIONS_4);
                }
                return fieldValue;
            });

    private static final Map<String, JexlExpression> EXPR_CACHE = new ConcurrentHashMap<>();

    // Namespaces exposed to filter expressions. Upstream also exposed (and whitelisted all of cn.hutool.core
    // for) obj/class/net/zip, which let an expression reflect, deserialize or touch files and the network:
    // remote code execution for anyone who can save an automation (upstream issue #130).
    private static final Map<String, Object> NAMESPACES = MapUtil.ofEntries(
            MapUtil.entry("str", StrUtil.class),
            MapUtil.entry("array", ArrayUtil.class),
            MapUtil.entry("coll", CollUtil.class),
            MapUtil.entry("id", IdUtil.class),
            MapUtil.entry("char", CharUtil.class),
            MapUtil.entry("random", RandomUtil.class),
            MapUtil.entry("escape", EscapeUtil.class),
            MapUtil.entry("hex", HexUtil.class),
            MapUtil.entry("date", DateUtil.class),
            MapUtil.entry("re", ReUtil.class),
            MapUtil.entry("num", NumberUtil.class)
    );

    // The namespace classes, their hutool superclasses (StrUtil's methods live in CharSequenceUtil, ...)
    // and DateTime, which date:* returns. No other hutool class is reachable.
    private static final Set<Class<?>> HUTOOL_ALLOWED = Stream.concat(
            NAMESPACES.values().stream()
                    .<Class<?>>map(c -> (Class<?>) c)
                    .flatMap(c -> Stream.<Class<?>>iterate(c, k -> k != null && isHutool(k), Class::getSuperclass)),
            Stream.of(DateTime.class)
    ).collect(Collectors.toUnmodifiableSet());

    private static boolean isHutool(Class<?> clazz) {
        return clazz.getName().startsWith("cn.hutool.");
    }

    private static final JexlPermissions PERMISSIONS = new JexlPermissions.Delegate(
            JexlPermissions.RESTRICTED.compose("telegram.files.repository.*")) {
        @Override
        public boolean allow(Package pack) {
            return pack.getName().startsWith("cn.hutool.") || super.allow(pack);
        }

        @Override
        public boolean allow(Class<?> clazz) {
            return isHutool(clazz) ? HUTOOL_ALLOWED.contains(clazz) : super.allow(clazz);
        }

        @Override
        public boolean allow(Method method) {
            return isHutool(method.getDeclaringClass()) ? HUTOOL_ALLOWED.contains(method.getDeclaringClass()) : super.allow(method);
        }

        @Override
        public boolean allow(Constructor<?> ctor) {
            return !isHutool(ctor.getDeclaringClass()) && super.allow(ctor);
        }

        @Override
        public boolean allow(Field field) {
            return !isHutool(field.getDeclaringClass()) && super.allow(field);
        }
    };

    private static final JexlEngine JEXL_ENGINE = new JexlBuilder()
            .strict(true)
            .silent(false)
            .permissions(PERMISSIONS)
            .namespaces(NAMESPACES)
            .create();

    public static JexlExpression getExpression(String exprStr) {
        return EXPR_CACHE.computeIfAbsent(exprStr, JEXL_ENGINE::createExpression);
    }

    public static List<TdApi.Message> filter(List<TdApi.Message> messages, String exprStr) {
        Predicate<TdApi.Message> predicate = filter(exprStr);
        return messages.parallelStream()
                .filter(predicate)
                .collect(Collectors.toList());
    }

    public static Predicate<TdApi.Message> filter(String exprStr) {
        if (StrUtil.isBlank(exprStr)) {
            return _ -> true;
        }
        JexlExpression expression = getExpression(exprStr);
        return message -> {
            Map<String, Object> map = BeanUtil.beanToMap(message, new LinkedHashMap<>(16, 1), BEAN_TO_MAP_OPTIONS);
            TdApiHelp.getFileHandler(message).ifPresent(fileHandler -> {
                FileRecord fileRecord = fileHandler.convertFileRecord(0);
                map.put("f", fileRecord);
            });
            MapContext context = new MapContext(map);

            try {
                Object result = expression.evaluate(context);
                return result instanceof Boolean && (Boolean) result;
            } catch (Exception e) {
                log.warn("Failed to evaluate expression: {}, message id: {}, error: {}",
                        exprStr, message.id, e.getMessage());
                return false;
            }
        };
    }
}
