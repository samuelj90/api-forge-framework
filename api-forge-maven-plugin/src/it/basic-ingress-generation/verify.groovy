// verify.groovy — Comprehensive verification with special characters / escaping checks

File generatedBase = new File(basedir, "target/generated-sources/api-forge/org/example/hello")

println "=== API-Forge Integration Test Verification Started ==="

// ===================================================================
// Helper function to check for HTML entities / bad escaping
// ===================================================================
def assertNoHtmlEntities(String content, String fileName) {
    assert !content.contains("&lt;") : "HTML entity &lt; found in $fileName — Mustache escaping is broken"
    assert !content.contains("&gt;") : "HTML entity &gt; found in $fileName — Mustache escaping is broken"
    assert !content.contains("&#61;") : "HTML entity &#61; found in $fileName — Mustache escaping is broken"
    assert !content.contains("&amp;") : "HTML entity &amp; found in $fileName"
    assert !content.contains("&quot;") : "HTML entity &quot; found in $fileName"
}

// ===================================================================
// 1. FILE EXISTENCE CHECKS
// ===================================================================

// Ingress
assert new File(generatedBase, "controller/HelloApiController.java").exists() : "Controller was not generated"
assert new File(generatedBase, "service/api/HelloApiService.java").exists() : "Service interface was not generated"
assert new File(generatedBase, "model/ingress/CreateGreetingRequest.java").exists() : "CreateGreetingRequest DTO missing"
assert new File(generatedBase, "model/ingress/GreetingResponse.java").exists() : "GreetingResponse DTO missing"

// Egress
assert new File(generatedBase, "client/inventory/InventoryApiClient.java").exists() : "Egress client was not generated"
assert new File(generatedBase, "model/egress/inventory/Product.java").exists() : "Product egress DTO was not generated"

// Service Impl
assert new File(basedir, "src/main/java/org/example/hello/service/impl/HelloApiServiceImpl.java").exists() :
        "Service impl scaffold was not generated"

// ===================================================================
// 2. NO ANONYMOUS SCHEMAS
// ===================================================================
assert !new File(generatedBase, "model/ingress/inline_response").exists() : "Anonymous schema generated"
assert !new File(generatedBase, "model/ingress/greetings_body").exists() : "Anonymous body schema generated"

// ===================================================================
// 3. READ ALL GENERATED FILES
// ===================================================================
String controller   = new File(generatedBase, "controller/HelloApiController.java").text
String serviceIfc   = new File(generatedBase, "service/api/HelloApiService.java").text
String requestDto   = new File(generatedBase, "model/ingress/CreateGreetingRequest.java").text
String responseDto  = new File(generatedBase, "model/ingress/GreetingResponse.java").text
String impl         = new File(basedir, "src/main/java/org/example/hello/service/impl/HelloApiServiceImpl.java").text
String client       = new File(generatedBase, "client/inventory/InventoryApiClient.java").text
String productDto   = new File(generatedBase, "model/egress/inventory/Product.java").text

// ===================================================================
// 4. SPECIAL CHARACTERS / ESCAPING CHECKS (ALL FILES)
// ===================================================================
[controller, serviceIfc, requestDto, responseDto, impl, client, productDto].eachWithIndex { content, i ->
    def names = ["Controller", "ServiceInterface", "CreateGreetingRequest", "GreetingResponse",
                 "ServiceImpl", "InventoryApiClient", "ProductDTO"]
    assertNoHtmlEntities(content, names[i])
}

// ===================================================================
// 5. INGRESS CONTROLLER CHECKS
// ===================================================================
assert controller.contains("@RestController")
assert controller.contains("@PostMapping")
assert controller.contains("@GetMapping")
assert controller.contains("@DeleteMapping")
assert controller.contains("ResponseEntity.status(201)")
assert controller.contains("ResponseEntity.status(204)")
assert controller.contains("ResponseEntity.status(200)")

// ===================================================================
// 6. DTO VALIDATION & TYPES
// ===================================================================
assert requestDto.contains("@NotBlank")
assert requestDto.contains("@Size(min = 1, max = 500)")
assert requestDto.contains("@Pattern(regexp")
assert requestDto.contains("@Min(1)")
assert requestDto.contains("@Max(5)")

assert responseDto.contains("OffsetDateTime") || responseDto.contains("java.time.OffsetDateTime")
assert responseDto.contains("List<String> tags") || responseDto.contains("List&lt;String&gt;")

assert productDto.contains("OffsetDateTime") || productDto.contains("java.time.OffsetDateTime")
assert productDto.contains("BigDecimal")

// ===================================================================
// 7. SERVICE LAYER
// ===================================================================
assert serviceIfc.contains("void deleteGreeting(String greetingId)")
assert serviceIfc.contains("GreetingResponse createGreeting")
assert !serviceIfc.contains(",)") : "Trailing comma in service method"

// ===================================================================
// 8. SERVICE IMPL
// ===================================================================
assert impl.contains("UnsupportedOperationException")
assert impl.contains("createGreeting")
assert impl.contains("deleteGreeting")

// ===================================================================
// 9. EGRESS CLIENT (Reactive)
// ===================================================================
assert client.contains("Mono<")
assert client.contains("List<Product>") || client.contains("List&lt;Product&gt;")
assert client.contains("listProducts")
assert client.contains("getProduct")
assert client.contains("deleteProduct")

println "✅ ALL TESTS PASSED — Including comprehensive special characters / escaping checks!"
return true