// The slide object model is the canonical contract from @educationagent/dsl. The renderer
// no longer vendors its own copy; it re-exports the DSL types here so the public
// `@educationagent/renderer/types` surface stays intact.
export * from '@educationagent/dsl';
export * from './effects';
