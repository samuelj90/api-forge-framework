// verify.groovy — executed by maven-invoker-plugin after the build

File generatedBase = new File(basedir, "target/generated-sources/api-forge/org/example/hello")

// Controller must be generated
assert new File(generatedBase, "controller/HelloApiController.java").exists() :
    "Controller was not generated"

// Service interface must be generated
assert new File(generatedBase, "service/api/HelloApiService.java").exists() :
    "Service interface was not generated"

// DTOs must be generated (not anonymous names)
assert new File(generatedBase, "model/ingress/CreateGreetingRequest.java").exists() :
    "CreateGreetingRequest DTO was not generated"
assert new File(generatedBase, "model/ingress/GreetingResponse.java").exists() :
    "GreetingResponse DTO was not generated"

// No anonymous schema artefacts
assert !new File(generatedBase, "model/ingress/inline_response_200.java").exists() :
    "Anonymous schema inline_response_200 was generated — $ref resolution is broken"
assert !new File(generatedBase, "model/ingress/greetings_body.java").exists() :
    "Anonymous schema greetings_body was generated — resolveFully is incorrectly true"

// Service impl scaffold must be generated (generateServiceImpl=true, file didn't exist)
assert new File(basedir, "src/main/java/org/example/hello/service/impl/HelloApiServiceImpl.java").exists() :
    "Service impl scaffold was not generated"

// Controller content checks
String controller = new File(generatedBase, "controller/HelloApiController.java").text
assert controller.contains("@RestController") : "Controller missing @RestController"
assert controller.contains("@PostMapping") : "Controller missing @PostMapping for createGreeting"
assert controller.contains("@GetMapping") : "Controller missing @GetMapping for listGreetings"
assert controller.contains("@DeleteMapping") : "Controller missing @DeleteMapping for deleteGreeting"
assert controller.contains("ResponseEntity.status(201)") : "createGreeting must return 201 from spec"
assert controller.contains("ResponseEntity.status(204)") : "deleteGreeting must return 204 from spec"
assert controller.contains("ResponseEntity.status(200)") : "listGreetings must return 200"
assert !controller.contains("&#61;") : "HTML entity &#61; found — Mustache escaping is broken"
assert !controller.contains("&lt;") : "HTML entity &lt; found — Mustache escaping is broken"
assert !controller.contains("orderId,)") : "Trailing comma in parameter list — generation bug"

// DTO validation annotations
String dto = new File(generatedBase, "model/ingress/CreateGreetingRequest.java").text
assert dto.contains("@NotBlank") : "Required String field must have @NotBlank"
assert dto.contains("@Size(min = 1") : "minLength=1 must produce @Size(min = 1)"
assert !dto.contains("&#61;") : "HTML entities found in DTO — Mustache escaping broken"

// Service interface content
String svc = new File(generatedBase, "service/api/HelloApiService.java").text
assert svc.contains("GreetingResponse createGreeting(CreateGreetingRequest request)") :
    "Service method signature incorrect"
assert svc.contains("void deleteGreeting(String greetingId)") :
    "deleteGreeting should return void"
assert !svc.contains(",)") : "Trailing comma in service method signature"

return true
