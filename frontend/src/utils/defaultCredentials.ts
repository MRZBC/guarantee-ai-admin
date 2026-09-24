/**
 * 初始密码在**前端**的镜像与提示文案。
 *
 * 为什么单独放一个文件：新建用户与重置密码成功时都要告诉管理员"该转达什么密码"，
 * 而这句话出现在 3 处（新建对话框提示、新建成功提示、重置成功提示）。
 * 每处各写一遍字面量必然漂移——本仓库已经因为"两处同值定义"栽过一次
 * （`DataInitializer.DEFAULT_PASSWORD` 与后端 `DefaultCredentials`），所以这里只留一处。
 *
 * ⚠️ **跨仓库镜像**：真正的权威定义是后端
 * `guarantee-common/.../security/DefaultCredentials.java` 的 `BUILT_IN_DEFAULT_PASSWORD`。
 * 改后端那个常量时**必须同步改这里**，否则界面会告知一个错误的密码，
 * 用户拿它登录不上、又看不出原因（这是最坏的一类缺陷：不报错，只是登不上）。
 */

/** 后端 `DefaultCredentials.BUILT_IN_DEFAULT_PASSWORD` 的镜像值。 */
export const BUILT_IN_DEFAULT_PASSWORD = 'User@123'

/** 覆盖它的配置项（后端 `DefaultCredentials.CONFIG_KEY`）。 */
export const DEFAULT_PASSWORD_CONFIG_KEY = 'app.security.default-password'

/**
 * 「初始密码是什么」的完整提示。
 *
 * 刻意**同时**写出内置值与配置项：D7=B 复用的是仓库公开常量（README 里就有），
 * 不回显它没有任何安全收益，却会让"重置完了该告诉用户什么"变成无法回答的问题。
 * 而一旦某次部署用配置覆盖了它，只写死内置值就会**误导**管理员——
 * 因此文案里点明"可被覆盖、以运维配置为准"，把这种情况也覆盖掉。
 */
export const DEFAULT_PASSWORD_HINT =
  `初始密码为系统默认密码（内置值 ${BUILT_IN_DEFAULT_PASSWORD}；`
  + `若部署已通过 ${DEFAULT_PASSWORD_CONFIG_KEY} 覆盖，请以运维配置为准）`
