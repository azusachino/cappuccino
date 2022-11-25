package cn.az.code.entity;

import java.util.Map;

/**
 * Coffee Shop
 */
public record CoffeeShop(String name, Map<String, Coffee> coffees) {

}
