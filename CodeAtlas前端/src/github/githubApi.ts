export type GitHubCredential = {
  id: string;
  label: string;
  scopes: string;
  createdAt: string;
  lastUsedAt: string | null;
  revokedAt: string | null;
};

export type GitHubBinding = {
  bound: boolean;
  projectId: string;
  credentialId: string;
  credentialLabel: string;
  repoUrl: string;
  ref: string | null;
  lastCommit: string | null;
  lastCheckedAt: string | null;
};

export type GitHubCheck = {
  projectId: string;
  repoUrl: string;
  ref: string | null;
  remoteCommit: string | null;
  snapshotCommit: string | null;
  changed: boolean;
  checkedAt: string;
  automatic: boolean;
};
