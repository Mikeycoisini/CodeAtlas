import { useEffect, useState } from "react";

export default function useStudyLocation() {
  const [query, setQuery] = useState(location.search);
  useEffect(() => { const update = () => setQuery(location.search); addEventListener("popstate", update); return () => removeEventListener("popstate", update); }, []);
  return new URLSearchParams(query);
}
