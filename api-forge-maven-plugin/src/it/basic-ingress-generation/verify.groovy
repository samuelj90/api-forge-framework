// verify.groovy — executed by maven-invoker-plugin after the build

File generatedBase = new File(basedir, "target/generated-sources/api-forge/org/example/hello")

// Controller must be generated
assert new File(generatedBase, "controller/HelloApiController.java").exists() : "Controller was not generated"
// Service interface must be generated
assert new File(generatedBase, "service/api/HelloApiService.java").exists() : "Service interface was not generated"
// DTOs must be generated (not anonymous names)
assert new File(generatedBase, "model/ingress/CreateGreetingRequest.java").exists() : "CreateGreetingRequest DTO was not generated"
assert new File(generatedBase, "model/ingress/GreetingResponse.java").exists() : "GreetingResponse DTO was not generated"

// No anonymous schema artefacts
assert !new File(generatedBase, "model/ingress/inline_response_200.java").exists() : "Anonymous schema inline_response_200 was generated — $ref resolution is broken"
assert !new File(generatedBase, "model/ingress/greetings_body.java").exists() : "Anonymous schema greetings_body was generated — resolveFully is incorrectly true"

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
assert !controller.contains("&lt;") : "HTML entity &lt; found — Mustache escaping is broken"
assert !controller.contains("&gt;") : "HTML entity &gt; found — Mustache escaping is broken"
assert !controller.contains("&#61;") : "HTML entity &#61; found — Mustache escaping is broken"
assert !controller.contains("orderId,)") : "Trailing comma in parameter list — generation bug"

// DTO validation annotations
String requestDto = new File(generatedBase, "model/ingress/CreateGreetingRequest.java").text
assert requestDto.contains("@NotBlank")
assert requestDto.contains("@Size(min = 1, max = 500)")
assert requestDto.contains("@Pattern(regexp")
assert requestDto.contains("@Min(1)")
assert requestDto.contains("@Max(5)")

String responseDto = new File(generatedBase, "model/ingress/GreetingResponse.java").text
assert responseDto.contains("OffsetDateTime")
assert responseDto.contains("List<String> tags")

// Service interface content
String svc = new File(generatedBase, "service/api/HelloApiService.java").text
assert svc.contains("GreetingResponse createGreeting(CreateGreetingRequest request)") : "Service method signature incorrect"
assert svc.contains("void deleteGreeting(String greetingId)") : "deleteGreeting should return void"
assert !svc.contains(",)") : "Trailing comma in service method signature"


// Service Impl
File implFile = new File(basedir, "src/main/java/org/example/hello/service/impl/HelloApiServiceImpl.java")
assert implFile.exists()
String impl = implFile.text
assert impl.contains("UnsupportedOperationException")
assert !impl.contains("&lt;") : "HTML entity &lt; found — Mustache escaping is broken"
assert !impl.contains("&gt;") : "HTML entity &gt; found — Mustache escaping is broken"
assert !impl.contains("&#61;") : "HTML entity &#61; found — Mustache escaping is broken"


// Client should be generated
assert new File(generatedBase, "client/inventory/InventoryApiClient.java").exists() :
"Egress client was not generated"

// DTOs should be generated
assert new File(generatedBase, "model/egress/inventory/Product.java").exists() :
        "Product DTO was not generated"

// Client content checks
String client = new File(generatedBase, "client/inventory/InventoryApiClient.java").text
assert client.contains("listProducts") : "listProducts method missing"
assert client.contains("getProduct") : "getProduct method missing"
assert client.contains("deleteProduct") : "deleteProduct method missing"
assert client.contains("Mono<List<Product>>") || client.contains("Mono<Product>") : "Reactive return types not rendered"
assert client.contains("List<Product>") : "List generic type not rendered"

// OffsetDateTime should be in the DTO, not necessarily in the client file
String productDto = new File(generatedBase, "model/egress/inventory/Product.java").text
assert productDto.contains("OffsetDateTime") ||
        productDto.contains("java.time.OffsetDateTime") :
        "Date-time type (OffsetDateTime) not handled in Product DTO"

assert !client.contains("&lt;") && !client.contains("&#61;") : "HTML escaping in client"
assert !productDto.contains("&lt;") : "HTML escaping in DTO"
println "✅ API-Forge Advanced Edge Case Verification PASSED"
return true
