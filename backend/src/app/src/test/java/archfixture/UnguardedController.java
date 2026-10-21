package archfixture;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.yourhrm.shared.auth.api.RequiresPermission;

/**
 * Deliberately broken controller (outside the application's component scan) used by ArchitectureTest to
 * prove that the US-CORE-039 rule detects an endpoint without a permission declaration.
 */
@RestController
public class UnguardedController {

    @GetMapping("/guarded")
    @RequiresPermission("core.role.view")
    public String guarded() {
        return "ok";
    }

    @GetMapping("/open")
    public String open() {
        return "oops";
    }
}
