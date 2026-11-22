# Third-party components

SnoreLog itself is licensed under the GNU GPL v3.0 (see `LICENSE`). It uses the following components, all under the Apache License 2.0 unless noted:

| Component | Use | Licence |
|---|---|---|
| [YAMNet](https://www.kaggle.com/models/google/yamnet) (Google) | On-device audio classifier used to recognise snoring. The model file is downloaded during the build and is not stored in this repository. | Apache 2.0 |
| [TensorFlow Lite](https://www.tensorflow.org/lite) (Google) | Runs the YAMNet model | Apache 2.0 |
| [AndroidX](https://developer.android.com/jetpack/androidx) (Google) | Core, AppCompat | Apache 2.0 |
| [Material Components for Android](https://github.com/material-components/material-components-android) (Google) | User interface | Apache 2.0 |
| [Guava](https://github.com/google/guava) (Google) | Required by AndroidX startup components | Apache 2.0 |

The full text of the Apache License 2.0 is at <https://www.apache.org/licenses/LICENSE-2.0>.

Apache 2.0 components are compatible with GPL-3.0 and are used under their own licence.
