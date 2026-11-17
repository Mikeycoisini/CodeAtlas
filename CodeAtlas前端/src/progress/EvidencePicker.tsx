import KnowledgePicker from "./KnowledgePicker";
import { write } from "../study/studyApi";

export default function EvidencePicker({ sourceType, sourceId, questionId, onAssociated, onClose }: {
  sourceType: string; sourceId: string; questionId?: string; onAssociated: () => void; onClose: () => void;
}) {
  return <KnowledgePicker onClose={onClose} onSelect={async knowledge => {
    await write("/progress/evidence", { knowledgeId: knowledge.id, sourceType, sourceId, questionId, idempotencyKey: crypto.randomUUID() });
    onAssociated();
  }}/>;
}
