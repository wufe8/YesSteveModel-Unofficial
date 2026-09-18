package com.fox.ysmu.util;

/**
 * Interface implemented by EntityArrow via Mixin to expose the
 * projectile model ID stored in the datawatcher.
 */
public interface IProjectileModelArrow {
    String ysmu$getProjectileModelId();

    /**
     * 射出这支箭的物品在**服务端**的注册名（如 {@code TConstruct:Crossbow}），没有则为空串。
     *
     * <p>1.7.10 的 {@code EntityArrow.shootingEntity} 只在服务端赋值、不同步给客户端，
     * 所以这个值由 {@code MixinEntityArrow} 在箭矢构造时写进 datawatcher 带过来，
     * 供 {@code ysm.shoot_item_id} 使用（折算规则见 {@link ProjectileShootItemIds}）。</p>
     */
    String ysmu$getShootItemId();
}
