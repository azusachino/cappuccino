package cn.az.code.controller;

import java.util.Arrays;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import cn.az.code.entity.Coffee;
import cn.az.code.entity.CoffeeShop;

/**
 * Coffee Controller
 */
@RestController
@RequestMapping("/api/v1/coffee")
public class CoffeeController {

    @GetMapping("/list")
    public ResponseEntity<?> listCoffees() {
        return ResponseEntity.ok().body(Arrays.asList("espresso"));
    }

    @GetMapping("/list/babies")
    public ResponseEntity<CoffeeShop> listCoffeeBabies() {
        var cappuccino = new Coffee("cappuccino", "none", 100);
        var data = Map.of("cappuccino", cappuccino);
        var cs = new CoffeeShop("rabbit-house", data);

        var oo = switch (cs.hashCode()) {
            case 0 -> {
                yield 0;
            }
            case 2 -> {
                yield 2;
            }
            default -> {
                yield -1;
            }
        };

        System.out.println(oo);

        return ResponseEntity.ok().body(cs);
    }
}
