package top.etca.renw;

/**
 * 任务数据实体类
 * 字段名称需与 items.json 中的键名保持一致，方便 Gson 自动解析
 */
public class Quest {
    public String id;
    public String name;
    public String condition_type;
    public String target;
    public int amount;
    public int reward;

    // 添加一个无参构造函数供 Gson 使用
    public Quest() {}

    public Quest(String id, String name, String condition_type, String target, int amount, int reward) {
        this.id = id;
        this.name = name;
        this.condition_type = condition_type;
        this.target = target;
        this.amount = amount;
        this.reward = reward;
    }
}