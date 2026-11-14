import {
  memoryIdentityKey,
  parseStructuredMemoryExtraction,
} from "./knowledge_policy.ts";

const validExtraction = {
  summary: "HOPE uses Kotlin and Supabase.",
  topics: ["HOPE", "Android"],
  projects: [{
    name: "HOPE AI",
    summary: "Android assistant project.",
    technologyStack: ["Kotlin", "Supabase"],
    repositoryUrl: "https://example.com/hope",
  }],
  entities: [{
    entityType: "project",
    name: "HOPE AI",
    properties: { platform: "Android" },
  }],
  relationships: [{
    source: "HOPE AI",
    target: "Supabase",
    relationship: "uses",
    confidence: 0.9,
  }],
  facts: [{
    memoryType: "project",
    content: "HOPE AI uses Kotlin.",
    entityName: "HOPE AI",
    attribute: "language",
    value: "Kotlin",
    sourceRole: "user",
    confidence: 0.95,
    projectName: "HOPE AI",
  }],
};

Deno.test("accepts bounded structured facts with source roles", () => {
  const extraction = parseStructuredMemoryExtraction(validExtraction);
  if (
    extraction?.facts.length !== 1 ||
    extraction.facts[0].sourceRole !== "user" ||
    extraction.projects[0].name !== "HOPE AI"
  ) {
    throw new Error("Expected structured source-linked memory data.");
  }
});

Deno.test("rejects malformed, oversized, and credential-like extracted facts", () => {
  if (
    parseStructuredMemoryExtraction({
      ...validExtraction,
      facts: [{ ...validExtraction.facts[0], content: "My password is secret." }],
    })
  ) {
    throw new Error("Credential-like memory must not be persisted.");
  }
  if (
    parseStructuredMemoryExtraction({
      ...validExtraction,
      relationships: Array(51).fill(validExtraction.relationships[0]),
    })
  ) {
    throw new Error("Oversized extraction output must be rejected.");
  }
});

Deno.test("creates stable identity keys for duplicate and conflicting fact detection", () => {
  const first = memoryIdentityKey("project", "HOPE AI", "language", "Uses Kotlin.");
  const second = memoryIdentityKey("project", " hope   ai ", " LANGUAGE ", "Uses Java.");
  if (first !== second) {
    throw new Error("Fact identity must not change when the value changes.");
  }
});
