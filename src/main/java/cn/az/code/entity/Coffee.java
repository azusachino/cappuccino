package cn.az.code.entity;

/**
 * Coffee 抽象类
 * 
 * @author az
 * 
 * @param name  名称
 * @param blend 口味
 * @param cnt   剩余数量
 */
public record Coffee(String name, String blend, int cnt) {

}
