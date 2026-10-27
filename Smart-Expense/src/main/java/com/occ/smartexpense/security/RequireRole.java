package com.occ.smartexpense.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 方法/类级角色校验，见 docs/12 §8。
 * 用法：{@code @RequireRole({"FINANCE","ADMIN"})}，满足任意一个即可。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireRole {

    String[] value();
}
