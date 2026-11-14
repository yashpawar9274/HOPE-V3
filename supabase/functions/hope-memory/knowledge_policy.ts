export type StructuredMemoryFact = {
  memoryType: "semantic" | "procedural" | "project" | "episodic";
  content: string;
  entityName: string | null;
  attribute: string | null;
  value: string | null;
  sourceRole: "user" | "assistant";
  confidence: number;
  projectName: string | null;
};

export type StructuredMemoryEntity = {
  entityType:
    | "person"
    | "project"
    | "organization"
    | "technology"
    | "preference"
    | "task"
    | "event"
    | "decision"
    | "repository"
    | "integration"
    | "other";
  name: string;
  properties: Record<string, string>;
};

export type StructuredMemoryRelationship = {
  source: string;
  target: string;
  relationship: string;
  confidence: number;
};

export type StructuredMemoryProject = {
  name: string;
  summary: string;
  technologyStack: string[];
  repositoryUrl: string | null;
};

export type StructuredMemoryExtraction = {
  summary: string;
  topics: string[];
  projects: StructuredMemoryProject[];
  entities: StructuredMemoryEntity[];
  relationships: StructuredMemoryRelationship[];
  facts: StructuredMemoryFact[];
};

const SECRET_PATTERN =
  /\b(password|passcode|one[\s-]?time\s+(?:password|code|pin)|\bOTP\b|CVV|security code|credit card number|debit card number|bank account number|access token|private key|service role key)\b/i;
const ENTITY_TYPES = new Set([
  "person",
  "project",
  "organization",
  "technology",
  "preference",
  "task",
  "event",
  "decision",
  "repository",
  "integration",
  "other",
]);

function cleanText(value: unknown, maximum: number): string | null {
  if (typeof value !== "string") return null;
  const clean = value.trim();
  if (!clean || clean.length > maximum || /[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]/.test(clean)) {
    return null;
  }
  return clean;
}

function cleanOptionalText(value: unknown, maximum: number): string | null | false {
  if (value === undefined || value === null || value === "") return null;
  return cleanText(value, maximum) ?? false;
}

function cleanStringArray(value: unknown, maximumItems: number, maximumLength: number): string[] | null {
  if (!Array.isArray(value) || value.length > maximumItems) return null;
  const items = value.map((item) => cleanText(item, maximumLength));
  if (items.some((item) => item === null)) return null;
  return [...new Set(items as string[])];
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

export function parseStructuredMemoryExtraction(
  value: unknown,
): StructuredMemoryExtraction | null {
  if (!isRecord(value)) return null;
  const summary = cleanText(value.summary, 12_000);
  const topics = cleanStringArray(value.topics, 10, 100);
  if (!summary || !topics || !Array.isArray(value.projects) || value.projects.length > 10 ||
    !Array.isArray(value.entities) || value.entities.length > 30 ||
    !Array.isArray(value.relationships) || value.relationships.length > 50 ||
    !Array.isArray(value.facts) || value.facts.length > 30) {
    return null;
  }

  const projects: StructuredMemoryProject[] = [];
  for (const raw of value.projects) {
    if (!isRecord(raw)) return null;
    const name = cleanText(raw.name, 160);
    const projectSummary = cleanText(raw.summary ?? "Project referenced in conversation.", 8_000);
    const technologyStack = cleanStringArray(raw.technologyStack, 30, 100);
    const repositoryUrl = cleanOptionalText(raw.repositoryUrl, 2_000);
    if (!name || !projectSummary || !technologyStack || repositoryUrl === false) return null;
    if (
      repositoryUrl !== null &&
      (!/^https:\/\//i.test(repositoryUrl) || /:\/\/[^/]*@/.test(repositoryUrl))
    ) {
      return null;
    }
    projects.push({ name, summary: projectSummary, technologyStack, repositoryUrl });
  }

  const entities: StructuredMemoryEntity[] = [];
  for (const raw of value.entities) {
    if (!isRecord(raw) || typeof raw.entityType !== "string" ||
      !ENTITY_TYPES.has(raw.entityType) || !isRecord(raw.properties)) {
      return null;
    }
    const name = cleanText(raw.name, 300);
    if (!name || Object.keys(raw.properties).length > 20) return null;
    const properties: Record<string, string> = {};
    for (const [key, propertyValue] of Object.entries(raw.properties)) {
      const cleanKey = cleanText(key, 100);
      const cleanValue = cleanText(propertyValue, 1_000);
      if (!cleanKey || !cleanValue) return null;
      properties[cleanKey] = cleanValue;
    }
    entities.push({
      entityType: raw.entityType as StructuredMemoryEntity["entityType"],
      name,
      properties,
    });
  }

  const relationships: StructuredMemoryRelationship[] = [];
  for (const raw of value.relationships) {
    if (!isRecord(raw)) return null;
    const source = cleanText(raw.source, 300);
    const target = cleanText(raw.target, 300);
    const relationship = cleanText(raw.relationship, 100);
    const confidence = raw.confidence;
    if (
      !source || !target || !relationship || typeof confidence !== "number" ||
      !Number.isFinite(confidence) || confidence < 0 || confidence > 1
    ) {
      return null;
    }
    relationships.push({ source, target, relationship, confidence });
  }

  const facts: StructuredMemoryFact[] = [];
  for (const raw of value.facts) {
    if (!isRecord(raw)) return null;
    const memoryType = raw.memoryType;
    const content = cleanText(raw.content, 20_000);
    const entityName = cleanOptionalText(raw.entityName, 300);
    const attribute = cleanOptionalText(raw.attribute, 300);
    const factValue = cleanOptionalText(raw.value, 8_000);
    const sourceRole = raw.sourceRole;
    const confidence = raw.confidence;
    const projectName = cleanOptionalText(raw.projectName, 160);
    if (
      !["semantic", "procedural", "project", "episodic"].includes(String(memoryType)) ||
      !content || SECRET_PATTERN.test(content) ||
      entityName === false || attribute === false || factValue === false ||
      (sourceRole !== "user" && sourceRole !== "assistant") ||
      typeof confidence !== "number" || !Number.isFinite(confidence) ||
      confidence < 0 || confidence > 1 || projectName === false
    ) {
      return null;
    }
    facts.push({
      memoryType: memoryType as StructuredMemoryFact["memoryType"],
      content,
      entityName,
      attribute,
      value: factValue,
      sourceRole,
      confidence,
      projectName,
    });
  }

  return { summary, topics, projects, entities, relationships, facts };
}

export function memoryIdentityKey(
  memoryType: string,
  entityName: string | null,
  attribute: string | null,
  content: string,
): string {
  const normalize = (value: string) =>
    value.trim().toLocaleLowerCase().replace(/\s+/g, " ");
  const identity = entityName && attribute
    ? `${normalize(memoryType)}|${normalize(entityName)}|${normalize(attribute)}`
    : `${normalize(memoryType)}|${normalize(content)}`;
  return identity.slice(0, 300);
}
