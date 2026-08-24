package com.fastpass.api.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class PageController {

    @GetMapping({"/admin", "/admin/"})
    public String adminPage() {
        return "forward:/admin/index.html";
    }
}