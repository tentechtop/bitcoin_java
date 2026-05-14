package com.bit.coin.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/bitcoin/system")
                .setViewName("forward:/home/index.html");

        registry.addViewController("/bitcoin/system/p2p")
                .setViewName("forward:/p2p-monitor.html");

    }
}