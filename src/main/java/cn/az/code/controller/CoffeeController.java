package cn.az.code.controller;

import java.util.Arrays;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
}
