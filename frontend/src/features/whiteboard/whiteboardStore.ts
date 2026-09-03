import {
  addEdge,
  applyEdgeChanges,
  applyNodeChanges,
  type Connection,
  type Edge,
  type EdgeChange,
  type Node,
  type NodeChange,
} from 'reactflow';
import { create } from 'zustand';
import type { Diagram } from '../../types/protocol';

let idSeq = 1;
const nextId = () => `n${idSeq++}`;

interface WhiteboardState {
  nodes: Node[];
  edges: Edge[];
  locked: boolean;
  onNodesChange: (changes: NodeChange[]) => void;
  onEdgesChange: (changes: EdgeChange[]) => void;
  onConnect: (conn: Connection) => void;
  addNode: (label: string) => void;
  renameNode: (id: string, label: string) => void;
  setLocked: (locked: boolean) => void;
  toDiagram: () => Diagram;
}

// FR1: structured shapes only (boxes, arrows, labels) - no freehand (Non-Goal v1).
export const useWhiteboardStore = create<WhiteboardState>((set, get) => ({
  nodes: [],
  edges: [],
  locked: false,

  onNodesChange: (changes) => {
    if (get().locked) return;
    set({ nodes: applyNodeChanges(changes, get().nodes) });
  },
  onEdgesChange: (changes) => {
    if (get().locked) return;
    set({ edges: applyEdgeChanges(changes, get().edges) });
  },
  onConnect: (conn) => {
    if (get().locked) return;
    set({ edges: addEdge({ ...conn, label: '' }, get().edges) });
  },

  addNode: (label) => {
    if (get().locked) return;
    const id = nextId();
    set({
      nodes: [
        ...get().nodes,
        {
          id,
          position: { x: 80 + Math.random() * 240, y: 80 + Math.random() * 240 },
          data: { label },
          type: 'default',
        },
      ],
    });
  },

  renameNode: (id, label) =>
    set({
      nodes: get().nodes.map((n) => (n.id === id ? { ...n, data: { ...n.data, label } } : n)),
    }),

  setLocked: (locked) => set({ locked }),

  toDiagram: () => ({
    nodes: get().nodes.map((n) => ({
      id: n.id,
      label: String((n.data as { label?: string }).label ?? ''),
      x: Math.round(n.position.x),
      y: Math.round(n.position.y),
    })),
    edges: get().edges.map((e) => ({
      id: e.id,
      source: e.source,
      target: e.target,
      label: typeof e.label === 'string' ? e.label : undefined,
    })),
  }),
}));
