package com.gaiys.proto.prototypehub.configuration.builder;

import com.gaiys.proto.prototypehub.configuration.customizer.PrototypeTextTermCustomizer;
import java.util.Arrays;
import java.util.List;
import java.util.function.BiFunction;
import java.util.stream.IntStream;
import org.hswebframework.ezorm.core.param.Term;
import org.hswebframework.ezorm.rdb.metadata.RDBColumnMetadata;
import org.hswebframework.ezorm.rdb.operator.builder.fragments.PrepareSqlFragments;
import org.hswebframework.ezorm.rdb.operator.builder.fragments.SqlFragments;
import org.hswebframework.ezorm.rdb.operator.builder.fragments.term.AbstractTermFragmentBuilder;
import org.jooq.Condition;
import org.jooq.Field;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;

/**
 * 原型查询的官方 EasyORM 条件扩展，以 jOOQ 结构化表达文本规则并绑定全部值。
 *
 * <p>只渲染条件，不执行数据库操作、不修改元数据或缓存授权结果。列名来自框架映射及关联别名，未知方言明确失败。
 *
 * @since 1.0.0
 * @see PrototypeTextTermCustomizer
 */
public final class PrototypeTextTermFragmentBuilder extends AbstractTermFragmentBuilder {
    private static final List<String> JAVA_WHITESPACE =
            IntStream.rangeClosed(Character.MIN_CODE_POINT, Character.MAX_CODE_POINT)
                    .filter(Character::isWhitespace)
                    .mapToObj(code -> new String(Character.toChars(code)))
                    .toList();

    public static final PrototypeTextTermFragmentBuilder OWNER_PRESENT =
            new PrototypeTextTermFragmentBuilder(
                    "prototype-owner-present", "原型所有者非空白", (field, term) -> ownerPresent(field));

    public static final PrototypeTextTermFragmentBuilder LITERAL_CONTAINS =
            new PrototypeTextTermFragmentBuilder(
                    "prototype-literal-contains",
                    "原型名称包含字面文本",
                    (field, term) -> DSL.position(field, DSL.val((String) term.getValue())).gt(0));

    /** 文案清单专用字面查询，不改变既有名称查询的大小写语义。 */
    public static final PrototypeTextTermFragmentBuilder COPY_LITERAL_CONTAINS =
            new PrototypeTextTermFragmentBuilder(
                    "prototype-copy-literal-contains",
                    "文案包含字面文本",
                    (field, term) ->
                            DSL.position(
                                            DSL.lower(field),
                                            DSL.lower(DSL.val((String) term.getValue())))
                                    .gt(0));

    private final BiFunction<Field<String>, Term, Condition> condition;

    private PrototypeTextTermFragmentBuilder(
            String termType, String name, BiFunction<Field<String>, Term, Condition> condition) {
        super(termType, name);
        this.condition = condition;
    }

    /**
     * 框架构建查询或计数时同步调用；列及别名由 EasyORM 提供，Term 由服务内部构造。
     *
     * <p>返回带参数的完整条件片段，不拼接 SQL 模板、不访问连接；渲染或方言错误向调用方传播，不能忽略筛选。
     */
    @Override
    public SqlFragments createFragments(
            String columnFullName, RDBColumnMetadata column, Term term) {
        SQLDialect dialect =
                switch (column.getDialect().getId()) {
                    case "PostgreSQL" -> SQLDialect.POSTGRES;
                    case "h2database" -> SQLDialect.H2;
                    default ->
                            throw new UnsupportedOperationException(
                                    "Prototype text queries are not supported for dialect: "
                                            + column.getDialect().getId());
                };
        var context = DSL.using(dialect);
        String[] identifier =
                Arrays.stream(columnFullName.split("\\."))
                        .map(part -> parsePlainName(part, column.getDialect()))
                        .toArray(String[]::new);
        var predicate = condition.apply(DSL.field(DSL.name(identifier), String.class), term);
        return PrepareSqlFragments.of(
                List.of(context.render(predicate)), context.extractBindValues(predicate));
    }

    /** 与跨表授权聚合共用空白owner规则，不能将旧无主项目向普通成员开放。 */
    public static Condition ownerPresent(Field<String> field) {
        // SQL TRIM 默认只处理空格；去掉 Java 的完整空白集合才能与详情入口 isBlank 保持同一规则。
        Field<String> remaining = field;
        for (String whitespace : JAVA_WHITESPACE) {
            remaining = DSL.replace(remaining, whitespace, "");
        }
        return remaining.ne("");
    }
}
