// 只提供本课堂需要的 Three 能力，避免把编辑器、加载器和远程资产链带进互动页。
import {
  Scene, PerspectiveCamera, OrthographicCamera, WebGLRenderer, Color, Vector2, Vector3,
  Euler, Quaternion, Matrix4, Group, Object3D, Mesh, Points, Line, LineLoop, LineSegments,
  BoxGeometry, SphereGeometry, PlaneGeometry, CylinderGeometry, ConeGeometry, TorusGeometry,
  TorusKnotGeometry, CircleGeometry, RingGeometry, IcosahedronGeometry, DodecahedronGeometry,
  OctahedronGeometry, TetrahedronGeometry, BufferGeometry, Float32BufferAttribute, EdgesGeometry,
  MeshBasicMaterial, MeshStandardMaterial, MeshPhongMaterial, MeshLambertMaterial, MeshNormalMaterial,
  LineBasicMaterial, PointsMaterial, AmbientLight, DirectionalLight, HemisphereLight, PointLight,
  AxesHelper, GridHelper, ArrowHelper, Box3, Clock, Raycaster, CanvasTexture, DoubleSide,
  SRGBColorSpace, PCFSoftShadowMap, MathUtils,
} from 'three';
import { OrbitControls } from 'three/addons/controls/OrbitControls.js';

window.THREE = Object.freeze({
  Scene, PerspectiveCamera, OrthographicCamera, WebGLRenderer, Color, Vector2, Vector3,
  Euler, Quaternion, Matrix4, Group, Object3D, Mesh, Points, Line, LineLoop, LineSegments,
  BoxGeometry, SphereGeometry, PlaneGeometry, CylinderGeometry, ConeGeometry, TorusGeometry,
  TorusKnotGeometry, CircleGeometry, RingGeometry, IcosahedronGeometry, DodecahedronGeometry,
  OctahedronGeometry, TetrahedronGeometry, BufferGeometry, Float32BufferAttribute, EdgesGeometry,
  MeshBasicMaterial, MeshStandardMaterial, MeshPhongMaterial, MeshLambertMaterial, MeshNormalMaterial,
  LineBasicMaterial, PointsMaterial, AmbientLight, DirectionalLight, HemisphereLight, PointLight,
  AxesHelper, GridHelper, ArrowHelper, Box3, Clock, Raycaster, CanvasTexture, DoubleSide,
  SRGBColorSpace, PCFSoftShadowMap, MathUtils, OrbitControls,
});
window.OrbitControls = OrbitControls;
