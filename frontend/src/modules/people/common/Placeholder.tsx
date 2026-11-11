import { Result } from 'antd';

/** Temporary content for a People screen that is not built yet (development scaffold). */
export function Placeholder({ area }: { area: string }) {
  return <Result status="info" title="Not built yet" subTitle={area} />;
}
