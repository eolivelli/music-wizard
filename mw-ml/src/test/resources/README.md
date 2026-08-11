# Test fixtures

Both `.onnx` files are generated, not downloaded, with the `onnx` Python
package. Committed because the fast suite is offline and the plumbing they
exercise does not depend on the model being real.

`identity-stem.onnx` (134 bytes): graph `y = Identity(x)`,
`x, y: float[2, num_splits, 512, 1024]`, opset 13, ir_version 8.

`zero-stem.onnx` (150 bytes): graph `y = Mul(x, 0.0)`, same shapes.

```python
from onnx import helper, TensorProto, numpy_helper, checker, save
import numpy as np
x = helper.make_tensor_value_info('x', TensorProto.FLOAT, [2, 'num_splits', 512, 1024])
y = helper.make_tensor_value_info('y', TensorProto.FLOAT, [2, 'num_splits', 512, 1024])
# identity-stem.onnx:
g = helper.make_graph([helper.make_node('Identity', ['x'], ['y'])], 'identity-stem', [x], [y])
# zero-stem.onnx:
zero = numpy_helper.from_array(np.zeros(1, dtype=np.float32), name='zero')
g = helper.make_graph([helper.make_node('Mul', ['x', 'zero'], ['y'])], 'zero-stem', [x], [y],
                      initializer=[zero])
m = helper.make_model(g, opset_imports=[helper.make_opsetid('', 13)], producer_name='mw-test')
m.ir_version = 8
checker.check_model(m)
save(m, 'out.onnx')
```
