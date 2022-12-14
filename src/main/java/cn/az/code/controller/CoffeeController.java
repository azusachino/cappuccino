package cn.az.code.controller;

import java.util.Arrays;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import cn.az.code.entity.Coffee;
import cn.az.code.entity.CoffeeShop;
import reactor.core.publisher.Mono;

/**
 * Coffee Controller
 */
@RestController
@RequestMapping("/api/v1/coffee")
public class CoffeeController {

    private static final Logger LOGGER = LoggerFactory.getLogger(CoffeeController.class);

    @GetMapping("/list")
    public Mono<ResponseEntity<?>> listCoffees() {
        return Mono.just(ResponseEntity.ok().body(Arrays.asList("espresso")));
    }

    @GetMapping("/list/babies")
    public Mono<ResponseEntity<CoffeeShop>> listCoffeeBabies() {
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

        LOGGER.info("what is this? {}", oo);

        return Mono.just(ResponseEntity.ok().body(cs));
    }
}
