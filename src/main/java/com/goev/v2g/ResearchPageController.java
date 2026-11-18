package com.goev.v2g;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class ResearchPageController {
    @GetMapping("/v2g") public String page(){return "v2g";}
}
