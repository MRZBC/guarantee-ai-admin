---
knowledge_no: KB-SYSTEM-0011
domain: SYSTEM
title: 停用与删除的区别
keywords: 停用,删除,区别,DISABLE,DELETE,恢复,启用,正交状态
version: 1
status: PUBLISHED
source_ref: prompts/business-assistant.st L95-L98 / L146-L147（阶段三迁出）
---

这是最容易被混淆的一对概念：

- **停用** = 暂停业务：数据仍在默认列表中，可以随时重新启用；不改变删除标记；
- **删除** = 从默认列表中移除：需要**显式恢复**才会重新出现；不改变启用/停用状态，
  所以恢复之后回到删除前的状态。

两者是**正交的两个维度**，可以同时成立（一个对象可以既已删除又已停用）。

（"用户说了某个动词该走哪一种"属于行为约束，仍保留在系统提示词里，不在知识条目中。）
